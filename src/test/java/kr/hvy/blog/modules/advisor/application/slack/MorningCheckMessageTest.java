package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.common.infrastructure.notification.slack.message.SlackColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 아침 점검 메시지: #hvy-advisor, 멘션 없음, 미국 마감 줄·지수별 예상 갭 줄·판정·면책, CAUTION 만 빨강.
 */
class MorningCheckMessageTest {

  @Test
  @DisplayName("블록 2개(본문·컨텍스트)에 미국 마감·지수 줄·판정이 들어가고 주의는 빨간 색상")
  void renders() {
    MorningCheckMessage message = MorningCheckMessage.builder().baseDate("2026-09-11")
        .usLine("미국 2026-09-11 마감: SPX +2.0% · SOX +3.0% · 환율 +0.1%")
        .indexLines(List.of("KOSPI 예상 갭 +1.20% (β 0.60×SPX, 임계 ±1.00%) → 어제 ▲ 강화", "KOSDAQ 예상 갭 +0.80% (β 0.80×COMP, 임계 ±1.00%) → 어제 ■ 유지"))
        .verdict(MorningVerdict.CAUTION).comment("역풍 갭").runId(31L).adviceId(842L).build();

    assertThat(message.getChannel()).isEqualTo("#hvy-advisor");
    assertThat(message.isNotify()).isFalse();
    assertThat(message.getFallbackText()).contains("2026-09-11").contains("CAUTION");
    assertThat(message.toBlocks()).hasSize(2);
    String body = ((MarkdownTextObject) ((SectionBlock) message.toBlocks().getFirst()).getText()).getText();
    assertThat(body).contains("아침 점검").contains("2026-09-11 판단").contains("SPX +2.0%").contains("KOSPI 예상 갭 +1.20%").contains("*판정 주의*")
        .contains("역풍 갭");
    String context = ((MarkdownTextObject) ((ContextBlock) message.toBlocks().get(1)).getElements().getFirst()).getText();
    assertThat(context).contains("run=31").contains("advice=842").contains("원 판단·채점은 그대로").contains(DailyAdviceMessage.DISCLAIMER);
    assertThat(message.toAttachments().getFirst().getColor()).isEqualTo(SlackColor.ERROR);
  }
}
