package kr.hvy.blog.modules.advisor.application.slack;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.slack.api.model.Attachment;
import com.slack.api.model.block.LayoutBlock;
import java.util.Collections;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 아침 점검 짧은 메시지 (#hvy-advisor, 멘션 없음). CAUTION 만 빨간 색상. 규칙 기반·보고 전용이며 원 판단을 바꾸지 않는다.
 */
@Builder
public class MorningCheckMessage implements SlackMessage {

  private final String baseDate;
  private final String usLine;
  private final List<String> indexLines;
  private final MorningVerdict verdict;
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
    return String.format("%s 판단 아침 점검: %s", baseDate, verdict);
  }

  @Override
  public List<LayoutBlock> toBlocks() {
    String text = String.format("🌅 *아침 점검* · %s 판단%n%s%n%s%n*판정 %s* — %s", baseDate, usLine, String.join("\n", indexLines), verdict.getDesc(),
        comment == null ? "" : comment);
    return List.of(
        section(s -> s.text(markdownText(text))),
        context(List.of(markdownText(String.format("run=%d · advice=%d · 규칙 기반(LLM 없음), 원 판단·채점은 그대로 · %s", runId, adviceId,
            DailyAdviceMessage.DISCLAIMER)))));
  }

  @Override
  public List<Attachment> toAttachments() {
    String color = switch (verdict) {
      case REINFORCE -> SlackColor.DEPLOY;
      case HOLD -> SlackColor.NOTICE;
      case CAUTION -> SlackColor.ERROR;
    };
    return Collections.singletonList(Attachment.builder().color(color).fallback(getFallbackText()).build());
  }
}
