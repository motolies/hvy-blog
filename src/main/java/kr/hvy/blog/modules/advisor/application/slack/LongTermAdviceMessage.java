package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 장기(H60·H180) 규칙 추천 Block Kit 메시지 (#hvy-advisor, 멘션 없음, M8).
 * <pre>
 * 헤더("📊 날짜 60일 관점 규칙 추천") → 판정 라벨("판정 불가: 표본 부족, 2년 이상 필요") → 국면·테마(맥락) → 적용 구간 → 팩터 가중치 → 종목 표(순위 코드 종목명 점수)
 * → 픽마다 서술(없으면 "서술 없음") → 총평 → run 메타 → 면책
 * </pre>
 * 일일 판단과 달리 LLM 국면 콜·확신이 없다(규칙 선택). 블록 수는 고정 ≤ 12 + 픽 수(≤ advisor.long-term.pick-count, 기본 10) 로 상한 50 안이다.
 */
@Builder
public class LongTermAdviceMessage implements SlackMessage {

  private final AdviceHeader header;
  private final List<PickRow> picks;
  private final Map<String, CandidateRow> candidates;
  /** 팩터 코드 → 사전 고정 가중치 */
  private final Map<String, Double> weights;
  /** 판정 라벨 (AdvisorKpiService.UNJUDGEABLE_LABEL) */
  private final String verdictLabel;
  /** LLM 서술이 실패해 규칙 픽만 발행하는지 */
  private final boolean narrativeFailed;
  private final String universeLabel;
  private final long runId;
  private final int promptTokens;
  private final int completionTokens;

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
    return String.format("%s %s, 종목 %d개 (%s)", header.baseDate(), kindLabel(), picks.size(), verdictLabel);
  }

  /** "60일 관점 규칙 추천" */
  String kindLabel() {
    return header.horizonDays() + "일 관점 규칙 추천";
  }

  String title() {
    return String.format("📊 %s %s (%d거래일)", header.baseDate(), kindLabel(), header.horizonDays());
  }

  @Override
  public List<LayoutBlock> toBlocks() {
    List<LayoutBlock> blocks = new ArrayList<>();
    blocks.add(header(h -> h.text(plainText(title()))));
    blocks.add(context(List.of(markdownText("⚖️ *" + verdictLabel + "* — 규칙(사전 고정 가중치 장기 팩터)이 고르고 LLM 은 서술만 붙였습니다. "
        + "60·180거래일 표본은 겹치는 창이라 유효 표본이 작아, 성과 수치는 참고용입니다."))));
    String regime = regimeText();
    if (regime != null) {
      blocks.add(section(s -> s.text(markdownText(regime))));
    }
    if (header.entryDate() != null && header.exitDate() != null) {
      blocks.add(section(s -> s.text(markdownText(String.format("*적용 구간*  %s 시가 진입 → %s 종가 청산 · %d거래일 (예정)",
          DailyAdviceMessage.dateWithDow(header.entryDate()), DailyAdviceMessage.dateWithDow(header.exitDate()), header.horizonDays())))));
    }
    blocks.add(section(s -> s.text(markdownText("*팩터 가중치*  " + weightText()))));
    blocks.add(divider());
    String universe = universeLabel == null || universeLabel.isBlank() ? "" : " · 유니버스: " + universeLabel.strip();
    blocks.add(section(s -> s.text(markdownText(String.format("*종목 (%d)%s*%n```%s```", picks.size(), universe, pickTable())))));
    if (narrativeFailed) {
      blocks.add(context(List.of(markdownText("⚠️ 서술 생성 실패 — 규칙 픽만 발행합니다(서술 없음)."))));
    }
    for (PickRow p : picks) {
      String note = pickNote(p);
      blocks.add(section(s -> s.text(markdownText(note))));
    }
    if (header.summary() != null && !header.summary().isBlank()) {
      blocks.add(section(s -> s.text(markdownText("_" + header.summary() + "_"))));
    }
    blocks.add(context(List.of(markdownText(String.format("run=%d · model=%s · prompt=%s · in %,d / out %,d tok",
        runId, header.model(), header.promptVersion(), promptTokens, completionTokens)))));
    blocks.add(context(List.of(markdownText(DailyAdviceMessage.DISCLAIMER))));
    return blocks;
  }

  @Override
  public List<Attachment> toAttachments() {
    return Collections.singletonList(Attachment.builder().color(SlackColor.NOTICE).fallback(getFallbackText()).build());
  }

  /**
   * 국면·테마 맥락 한 줄(선택에는 적용되지 않음): "*국면(규칙, 맥락)*  BEAR · 변동성 HIGH(87%) · 테마 강: 5[삼성전자] +3.1%p". 국면이 없으면 null.
   */
  String regimeText() {
    MarketRegime r = header.regime();
    if (r == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder("*국면(규칙, 맥락)*  ");
    sb.append(r.trend() == null ? "-" : r.trend().getCode());
    sb.append(" · 변동성 ").append(r.vol() == null ? VolRegimeCode.UNKNOWN.getCode() : r.vol().getCode());
    if (r.volPct() != null) {
      sb.append(String.format("(%.0f%%)", r.volPct() * 100));
    }
    if (r.themes() != null && !r.themes().isEmpty()) {
      sb.append(" · 테마 강: ").append(r.themes().stream().limit(DailyAdviceMessage.THEME_BRIEF).map(DailyAdviceMessage::themeBrief)
          .collect(Collectors.joining(" · ")));
    }
    return sb.toString();
  }

  /** "MOM_12_1 0.30 · QUALITY_ROE 0.20 · …" (설정 순) */
  String weightText() {
    if (weights == null || weights.isEmpty()) {
      return "-";
    }
    return weights.entrySet().stream().map(e -> String.format("%s %.2f", e.getKey(), e.getValue())).collect(Collectors.joining(" · "));
  }

  /**
   * 고정폭 표: 순위(4) 코드(6) 종목명(13) 점수(5). 확신 열이 없다(규칙 선택).
   */
  String pickTable() {
    StringBuilder sb = new StringBuilder();
    sb.append(SlackWidth.padRight("순위", 4)).append(' ').append(SlackWidth.padRight("코드", 6)).append(' ')
        .append(SlackWidth.padRight("종목명", DailyAdviceMessage.NAME_CELLS)).append(' ').append("점수").append('\n');
    for (PickRow p : picks) {
      CandidateRow c = candidates == null ? null : candidates.get(p.ticker());
      String name = c == null || c.stockName() == null ? p.ticker() : c.stockName();
      String score = c == null ? "-" : String.format("%+.2f", c.quantScore());
      sb.append(SlackWidth.padRight(String.format("%2d", p.pickRank()), 4)).append(' ')
          .append(SlackWidth.padRight(p.ticker(), 6)).append(' ')
          .append(SlackWidth.padRight(name, DailyAdviceMessage.NAME_CELLS)).append(' ')
          .append(score).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /**
   * 픽 1개의 서술 인용 블록: "> *1 삼성전자(005930)* 서술" + 리스크 줄. 서술이 없으면 "서술 없음".
   */
  String pickNote(PickRow p) {
    CandidateRow c = candidates == null ? null : candidates.get(p.ticker());
    String name = c == null || c.stockName() == null ? p.ticker() : c.stockName();
    StringBuilder sb = new StringBuilder();
    sb.append("> *").append(p.pickRank()).append(' ').append(name).append('(').append(p.ticker()).append(")* ")
        .append(DailyAdviceMessage.quoted(p.thesis()));
    if (p.riskNote() != null && !p.riskNote().isBlank()) {
      sb.append("\n> ⚠ ").append(DailyAdviceMessage.quoted(p.riskNote()));
    }
    return sb.toString();
  }
}
