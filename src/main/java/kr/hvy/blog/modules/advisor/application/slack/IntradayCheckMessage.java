package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.util.Collections;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 장중 점검 짧은 메시지 (#hvy-advisor, 멘션 없음). OFF_TRACK 만 빨간 색상. 보고 전용이며 학습에 쓰지 않는다.
 */
@Builder
public class IntradayCheckMessage implements SlackMessage {

  private final String baseDate;
  private final String checkedAt;
  private final String indexLine;
  private final int agreed;
  private final int total;
  private final IntradayVerdict verdict;
  private final String comment;
  private final long runId;
  private final long adviceId;

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
    String text = String.format("🕛 *%s 중간 점검* · %s 판단%n%s · 픽 일치 %s → *%s*%n%s", checkedAt, baseDate, indexLine, ratio,
        verdict.getDesc(), comment == null ? "" : comment);
    return List.of(
        section(s -> s.text(markdownText(text))),
        context(List.of(markdownText(String.format("run=%d · advice=%d · %s", runId, adviceId, DailyAdviceMessage.DISCLAIMER)))));
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
