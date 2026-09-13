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
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import lombok.Builder;

/**
 * 주간 검토 보고 (#hvy-advisor, 멘션 없음): KPI(변형 3종)·IC 표·가중치 변경·보정 표·교훈 변동·재현성·데이터 품질. 각 절은 호출자가 줄로 만든다.
 */
@Builder
public class WeeklyReviewMessage implements SlackMessage {

  private final String periodLabel;
  private final List<String> kpiLines;
  private final List<String> icLines;
  private final List<String> weightLines;
  private final List<String> calibrationLines;
  private final List<String> lessonLines;
  private final List<String> reproducibilityLines;
  private final List<String> warnings;
  private final long runId;

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
    return "주간 검토 " + periodLabel;
  }

  @Override
  public List<LayoutBlock> toBlocks() {
    List<LayoutBlock> blocks = new ArrayList<>();
    blocks.add(header(h -> h.text(plainText("🧠 주간 검토 (" + periodLabel + ")"))));
    add(blocks, "*KPI (5거래일, 품질 OK)*", kpiLines);
    add(blocks, "*시그널 IC*", icLines);
    add(blocks, "*가중치*", weightLines);
    add(blocks, "*신뢰도 보정*", calibrationLines);
    add(blocks, "*교훈*", lessonLines);
    add(blocks, "*재현성*", reproducibilityLines);
    if (warnings != null && !warnings.isEmpty()) {
      blocks.add(divider());
      blocks.add(section(s -> s.text(markdownText("⚠️ " + String.join("\n⚠️ ", warnings)))));
    }
    blocks.add(context(List.of(markdownText("run=" + runId + " · " + DailyAdviceMessage.DISCLAIMER))));
    return blocks;
  }

  private static void add(List<LayoutBlock> blocks, String title, List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return;
    }
    blocks.add(divider());
    blocks.add(section(s -> s.text(markdownText(title + "\n" + String.join("\n", lines)))));
  }

  @Override
  public List<Attachment> toAttachments() {
    return Collections.singletonList(Attachment.builder().color(SlackColor.NOTICE).fallback(getFallbackText()).build());
  }
}
