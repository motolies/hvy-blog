package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.MorningAdviceGuard;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 아침 재판정 메시지 (#hvy-advisor, 멘션 없음). "어제 저녁 대비" diff.
 * <pre>
 * 헤더 → 밤사이 요약 → 조치 표(고정폭: 조치 코드 종목명 방향 확신) → 조치마다 사유 section(유지 → 제외 → 추가) → 총평 → run 메타 → 면책
 * </pre>
 * 사유는 가드가 300자로 자르므로 조치마다 section 1개(3,000자 상한 안)에 싣는다. 블록 수는 고정 7 + 조치 수(유지+제외 ≤ pick-max 10, 추가 ≤ 10) 로 50 안이다.
 * 막대 색: 트리거일(|갭| ≥ σ·섹터 2σ·CAUTION) 빨강, 조치 변화(제외·추가)가 있으면 초록, 전부 유지면 파랑 — 트리거는 호출 여부가 아니라 표시용이다.
 */
@Builder
public class MorningAdviceMessage implements SlackMessage {

  /** 표 종목명 열 폭(칸) — 전체 39칸 안(DailyAdviceMessage 와 같은 모바일 기준) */
  static final int NAME_CELLS = 13;

  private final LocalDate baseDate;
  private final LocalDate entryDate;
  private final LocalDate exitDate;
  private final List<String> overnightLines;
  private final List<PickRow> kept;
  private final List<PickRow> added;
  private final List<MorningAdviceGuard.Drop> drops;
  /** 티커 → 종목명 (저녁 후보 스냅샷) */
  private final Map<String, String> names;
  private final String summary;
  private final boolean triggered;
  private final long runId;
  private final long adviceId;
  private final long parentAdviceId;

  @Override
  public String getChannel() {
    return SlackChannel.ADVISOR.getChannel();
  }

  @Override
  public boolean isNotify() {
    return false;
  }

  @Override
  public String getFallbackText() {
    return String.format("%s 판단 아침 재판정: 유지 %d · 제외 %d · 추가 %d", baseDate, size(kept), size(drops), size(added));
  }

  @Override
  public List<LayoutBlock> toBlocks() {
    List<LayoutBlock> blocks = new ArrayList<>();
    blocks.add(header(h -> h.text(plainText(String.format("🌄 아침 재판정 · %s 저녁 대비", DailyAdviceMessage.dateWithDow(baseDate))))));
    String window = entryDate == null || exitDate == null ? "" : String.format("적용 구간: %s 시가 → %s 종가 (저녁과 같음)%n",
        DailyAdviceMessage.dateWithDow(entryDate), DailyAdviceMessage.dateWithDow(exitDate));
    blocks.add(section(s -> s.text(markdownText(window + String.join("\n", overnightLines == null ? List.of() : overnightLines)))));
    blocks.add(divider());
    blocks.add(section(s -> s.text(markdownText(String.format("*유지 %d · 제외 %d · 추가 %d*%n```%s```", size(kept), size(drops), size(added), table())))));
    for (PickRow p : safe(kept)) {
      blocks.add(section(s -> s.text(markdownText(reasonLine("유지", p.ticker(), p.actionReason())))));
    }
    for (MorningAdviceGuard.Drop d : safe(drops)) {
      blocks.add(section(s -> s.text(markdownText(reasonLine("제외", d.evening().ticker(), d.reason())))));
    }
    for (PickRow p : safe(added)) {
      String text = reasonLine("추가", p.ticker(), p.actionReason())
          + (p.thesis() == null ? "" : "\n" + DailyAdviceMessage.quoted("근거: " + p.thesis()))
          + (p.riskNote() == null ? "" : "\n" + DailyAdviceMessage.quoted("리스크: " + p.riskNote()));
      blocks.add(section(s -> s.text(markdownText(text))));
    }
    if (summary != null && !summary.isBlank()) {
      blocks.add(divider());
      blocks.add(section(s -> s.text(markdownText("*총평* " + summary))));
    }
    blocks.add(context(List.of(markdownText(String.format("run=%d · advice=%d (저녁 advice=%d) · 채점은 저녁과 같은 창 · 트리거일=%s", runId, adviceId,
        parentAdviceId, triggered ? "예" : "아니오")))));
    blocks.add(context(List.of(markdownText(DailyAdviceMessage.DISCLAIMER))));
    return blocks;
  }

  @Override
  public List<Attachment> toAttachments() {
    String color = triggered ? SlackColor.ERROR : safe(drops).isEmpty() && safe(added).isEmpty() ? SlackColor.NOTICE : SlackColor.DEPLOY;
    return Collections.singletonList(Attachment.builder().color(color).fallback(getFallbackText()).build());
  }

  /**
   * 고정폭 조치 표: 조치(2) 코드(6) 종목명(13) 방향(2) 확신(4). 순서는 유지(최종 순위) → 제외(저녁 순위) → 추가(최종 순위).
   */
  String table() {
    List<String> rows = new ArrayList<>();
    rows.add(row("조치", "코드", "종목명", "방향", "확신"));
    for (PickRow p : safe(kept)) {
      rows.add(row(label(PickAction.KEEP), p.ticker(), names(p.ticker()), direction(p.direction()), String.format("%.2f", p.conviction())));
    }
    for (MorningAdviceGuard.Drop d : safe(drops)) {
      PickRow e = d.evening();
      rows.add(row(label(PickAction.DROP), e.ticker(), names(e.ticker()), direction(e.direction()), String.format("%.2f", e.conviction())));
    }
    for (PickRow p : safe(added)) {
      rows.add(row(label(PickAction.ADD), p.ticker(), names(p.ticker()), direction(p.direction()), String.format("%.2f", p.conviction())));
    }
    return String.join("\n", rows);
  }

  private String reasonLine(String label, String ticker, String reason) {
    return String.format("*%s* %s `%s` — %s", label, names(ticker), ticker, reason == null || reason.isBlank() ? "(사유 없음)" : reason);
  }

  private String names(String ticker) {
    String name = names == null ? null : names.get(ticker);
    return name == null ? ticker : name;
  }

  private static String row(String action, String ticker, String name, String direction, String conviction) {
    return SlackWidth.padRight(action, 5) + SlackWidth.padRight(ticker, 7) + SlackWidth.padRight(name, NAME_CELLS) + " "
        + SlackWidth.padRight(direction, 5) + conviction;
  }

  private static String label(PickAction action) {
    return action.getDesc();
  }

  private static String direction(PickDirection direction) {
    return direction == null ? "-" : direction == PickDirection.LONG ? "매수" : "회피";
  }

  private static <T> List<T> safe(List<T> list) {
    return list == null ? List.of() : list;
  }

  private static int size(List<?> list) {
    return list == null ? 0 : list.size();
  }
}
