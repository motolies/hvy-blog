package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 장중 점검 짧은 메시지 (#hvy-advisor, 멘션 없음). OFF_TRACK 만 빨간 색상. 보고 전용이며 학습에 쓰지 않는다.
 * <p>
 * note-v1(2026-09-21): 픽별 편차 줄(≤10, "종목명 시가대비 +0.8% · 지수대비 +0.5% (1.3σ) · 클래스 · why 요약")을 section 1~2개로 덧붙인다 — 한 section 은 3,000자
 * 상한이라 {@link #SECTION_BUDGET} 마다 나눈다. 정규 점검 창(KST 11:30~12:30) 밖의 수동 실행은 제목에 "장외 점검" 을 표기한다(현재가=종가라 반나절 해석이 깨진다).
 */
@Builder
public class IntradayCheckMessage implements SlackMessage {

  /** section 텍스트 상한 3,000자에서 여유를 둔 예산 */
  static final int SECTION_BUDGET = 2_800;
  /** 픽 줄 상한 (pick-max 와 같다) */
  public static final int MAX_PICK_LINES = 10;

  private final String baseDate;
  private final String checkedAt;
  private final String indexLine;
  private final int agreed;
  private final int total;
  private final IntradayVerdict verdict;
  private final String comment;
  private final long runId;
  private final long adviceId;
  /** 픽별 편차 줄 (없으면 null 또는 빈 목록) */
  private final List<String> pickLines;
  /** 정규 점검 창 밖 실행 */
  private final boolean offHours;

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
    return String.format("%s 장중 점검: %s (%d/%d)", baseDate, verdict, agreed, total);
  }

  @Override
  public List<LayoutBlock> toBlocks() {
    String ratio = total == 0 ? "-" : String.format("%d/%d (%.0f%%)", agreed, total, agreed * 100.0 / total);
    String text = String.format("🕛 *%s 중간 점검%s* · %s 판단%n%s · 픽 일치 %s → *%s*%n%s", checkedAt, offHours ? " (장외 점검)" : "", baseDate, indexLine,
        ratio, verdict.getDesc(), comment == null ? "" : comment);
    List<LayoutBlock> blocks = new ArrayList<>();
    blocks.add(section(s -> s.text(markdownText(text))));
    for (String chunk : pickChunks()) {
      blocks.add(section(s -> s.text(markdownText(chunk))));
    }
    blocks.add(context(List.of(markdownText(String.format("run=%d · advice=%d · %s", runId, adviceId, DailyAdviceMessage.DISCLAIMER)))));
    return blocks;
  }

  /**
   * 픽 줄(≤10)을 section 예산(2,800자) 단위로 묶는다. 줄이 없으면 빈 목록.
   */
  List<String> pickChunks() {
    List<String> chunks = new ArrayList<>();
    if (pickLines == null || pickLines.isEmpty()) {
      return chunks;
    }
    StringBuilder sb = new StringBuilder("*픽 편차*");
    int lines = 0;
    for (String line : pickLines) {
      if (lines >= MAX_PICK_LINES) {
        break;
      }
      if (sb.length() + line.length() + 1 > SECTION_BUDGET) {
        chunks.add(sb.toString());
        sb = new StringBuilder();
      }
      sb.append('\n').append(line);
      lines++;
    }
    if (!sb.isEmpty()) {
      chunks.add(sb.toString());
    }
    return chunks;
  }

  @Override
  public List<Attachment> toAttachments() {
    String color = switch (verdict) {
      case ON_TRACK -> SlackColor.DEPLOY;
      case MIXED -> SlackColor.NOTICE;
      case OFF_TRACK -> SlackColor.ERROR;
    };
    return Collections.singletonList(Attachment.builder().color(color).fallback(getFallbackText()).build());
  }
}
