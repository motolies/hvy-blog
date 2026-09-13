package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slack 메시지 규약: #hvy-advisor, 멘션 없음, 헤더·국면·섹터·종목 표(코드 블록)·컨텍스트·면책 고정 문구.
 */
class DailyAdviceMessageTest {

  @Test
  @DisplayName("블록에 국면·섹터·종목 표·run 컨텍스트·면책이 들어가고 채널은 #hvy-advisor, 멘션 없음")
  void rendersBlocks() {
    AdviceHeader header = AdviceHeader.builder().adviceId(842L).runId(1284L).baseDate(LocalDate.of(2026, 9, 11)).adviceKind("DAILY")
        .variant(AdviceVariant.LIVE).horizonDays(5).regimeCode(MarketRegimeCode.RISK_ON).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL)
        .pUp(0.7).regimeRationale("반도체 수급 개선").leadingSectors(List.of(new SectorCall("G2510", "반도체", "외인"))).summary("총평")
        .promptVersion("advice-v1").model("judge-x").build();
    List<PickRow> picks = List.of(
        PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.8).thesis("외인 5일 순매수 상위, 52주 고점 -2%").build(),
        PickRow.builder().ticker("000660").pickRank(2).direction(PickDirection.AVOID).conviction(0.6).thesis("수급 이탈").build());
    Map<String, CandidateRow> candidates = Map.of(
        "005930", CandidateRow.builder().ticker("005930").stockName("삼성전자").sectorName("반도체").quantScore(0.72).build(),
        "000660", CandidateRow.builder().ticker("000660").stockName("SK하이닉스").sectorName("반도체").quantScore(0.68).build());
    DailyAdviceMessage message = DailyAdviceMessage.builder().header(header).picks(picks).candidates(candidates)
        .scoreboardLines(List.of("5일 적중 54.8% (n=84)")).runId(1284L).promptTokens(6180).completionTokens(1940).costText("$0.0123").degraded(true).build();

    assertThat(message.getChannel()).isEqualTo("#hvy-advisor");
    assertThat(message.isNotify()).isFalse();
    assertThat(message.getFallbackText()).contains("2026-09-11").contains("RISK_ON").contains("2");

    List<LayoutBlock> blocks = message.toBlocks();
    assertThat(blocks.getFirst()).isInstanceOf(HeaderBlock.class);
    assertThat(((HeaderBlock) blocks.getFirst()).getText().getText()).isEqualTo("📈 2026-09-11 시장 판단 (5거래일)");
    String all = blocks.stream().map(DailyAdviceMessageTest::text).reduce("", String::concat);
    assertThat(all).contains("위험 선호").contains("KOSPI ▲ / KOSDAQ ■").contains("확신 0.70").contains("반도체(G2510)")
        .contains("삼성전자").contains("SK하이닉스").contains("회피").contains("매수").contains("0.72")
        .contains("5일 적중 54.8%").contains("run=1284").contains("model=judge-x").contains("in 6,180 / out 1,940").contains("$0.0123")
        .contains("수집 결손일").contains(DailyAdviceMessage.DISCLAIMER);
    assertThat(blocks.getLast()).isInstanceOf(ContextBlock.class);
    assertThat(message.toAttachments()).hasSize(1);
    assertThat(message.pickTable()).startsWith("순위").contains("삼성전자").doesNotContain("```");
    assertThat(all).contains("```");
  }

  private static String text(LayoutBlock block) {
    if (block instanceof SectionBlock s && s.getText() instanceof MarkdownTextObject m) {
      return m.getText();
    }
    if (block instanceof ContextBlock c) {
      return c.getElements().stream().filter(e -> e instanceof MarkdownTextObject).map(e -> ((MarkdownTextObject) e).getText()).reduce("", String::concat);
    }
    if (block instanceof HeaderBlock h) {
      return h.getText().getText();
    }
    return "";
  }
}
