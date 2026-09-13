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
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slack 메시지 규약: #hvy-advisor, 멘션 없음, 헤더·추세·국면·추세 전망·데이터 기준·적용 구간·섹터·종목 표(코드 블록, 종목코드 열)·근거 줄·컨텍스트·면책 고정 문구.
 */
class DailyAdviceMessageTest {

  @Test
  @DisplayName("블록에 추세·국면·전망·데이터 기준·섹터·종목 표(코드)·근거·run 컨텍스트·면책이 들어가고 채널은 #hvy-advisor, 멘션 없음")
  void rendersBlocks() {
    MarketTrend kospiTrend = MarketTrend.builder().indexCode("0001").code(MarketTrendCode.BULL).rawCode(MarketTrendCode.BULL).score(4)
        .since(LocalDate.of(2026, 7, 28)).days(32).close(2731.44).ma20(2612.4).ma60(2540.1).build();
    MarketTrend kosdaqTrend = MarketTrend.builder().indexCode("1001").code(MarketTrendCode.SIDEWAYS).rawCode(MarketTrendCode.SIDEWAYS).score(1)
        .since(LocalDate.of(2026, 9, 2)).days(7).close(812.3).ma20(805.0).ma60(790.2).build();
    AdviceHeader header = AdviceHeader.builder().adviceId(842L).runId(1284L).baseDate(LocalDate.of(2026, 9, 11)).adviceKind("DAILY")
        .variant(AdviceVariant.LIVE).horizonDays(5).regimeCode(MarketRegimeCode.RISK_ON).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL)
        .pUp(0.7).regimeRationale("반도체 수급 개선").leadingSectors(List.of(new SectorCall("G2510", "반도체", "외인"))).summary("총평")
        .trendKospi(MarketTrendCode.BULL).trendKosdaq(MarketTrendCode.SIDEWAYS).trends(List.of(kospiTrend, kosdaqTrend))
        .outlooks(List.of(new TrendOutlook("0001", TrendHorizon.BEYOND_20D, 0.70, InvalidationType.BELOW_MA20),
            new TrendOutlook("1001", TrendHorizon.WITHIN_5D, 0.60, InvalidationType.NONE)))
        .dataAsOf(Map.of("domestic", "2026-09-11", "flow", "2026-09-11", "flowProvisional", true, "sector", "2026-09-11", "global", "2026-09-09",
            "globalAgeTradingDays", 2))
        .entryDate(LocalDate.of(2026, 9, 14)).exitDate(LocalDate.of(2026, 9, 18))
        .promptVersion("advice-v2").model("judge-x").build();
    // advice-v5: 프롬프트 상한(thesis 300자·risk 150자) 그대로 전문이 실린다
    String thesis300 = "근거".repeat(150);
    String risk150 = "위험".repeat(75);
    List<PickRow> picks = List.of(
        PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.8).thesis("외인 5일 순매수 상위, 52주 고점 -2%")
            .riskNote("환율 급등 시 수급 반전").build(),
        PickRow.builder().ticker("000660").pickRank(2).direction(PickDirection.AVOID).conviction(0.6).thesis("수급 이탈").build(),
        PickRow.builder().ticker("035420").pickRank(3).direction(PickDirection.LONG).conviction(0.7).thesis(thesis300).riskNote(risk150).build());
    Map<String, CandidateRow> candidates = Map.of(
        "005930", CandidateRow.builder().ticker("005930").stockName("삼성전자").sectorName("반도체").quantScore(0.72).build(),
        "000660", CandidateRow.builder().ticker("000660").stockName("SK하이닉스").sectorName("반도체").quantScore(0.68).build(),
        "035420", CandidateRow.builder().ticker("035420").stockName("NAVER").sectorName("서비스").quantScore(0.65).build());
    DailyAdviceMessage message = DailyAdviceMessage.builder().header(header).picks(picks).candidates(candidates).marketLabel("KOSPI")
        .scoreboardLines(List.of("5일 적중 54.8% (n=84)")).runId(1284L).promptTokens(6180).completionTokens(1940).costText("$0.0123").degraded(true).build();

    assertThat(message.getChannel()).isEqualTo("#hvy-advisor");
    assertThat(message.isNotify()).isFalse();
    assertThat(message.getFallbackText()).contains("2026-09-11").contains("RISK_ON").contains("종목 3개");

    List<LayoutBlock> blocks = message.toBlocks();
    assertThat(blocks.getFirst()).isInstanceOf(HeaderBlock.class);
    assertThat(((HeaderBlock) blocks.getFirst()).getText().getText()).isEqualTo("📈 2026-09-11 시장 판단 (5거래일)");
    String all = blocks.stream().map(DailyAdviceMessageTest::text).reduce("", String::concat);
    assertThat(all).contains("*추세*  KOSPI 강세 32일째 (+4) · KOSDAQ 보합 7일째 (+1)")
        .contains("위험 선호").contains("KOSPI ▲ / KOSDAQ ■").contains("확신 0.70")
        .contains("*추세 전망*  KOSPI 20거래일 넘게 유지 · 확신 0.70 · 무효화 20일선(2,612) 하향 이탈")
        .contains("KOSDAQ 5거래일 안에 전환 · 확신 0.60 · 무효화 없음")
        .contains("*데이터 기준*  국내 09-11 종가 · 수급 09-11(잠정) · 미국 09-09 종가 ⚠️ 2영업일 지연")
        .contains("*적용 구간*  09-14(월) 시가 진입 → 09-18(금) 종가 청산 · 5거래일 (예정)")
        .contains("반도체(G2510)")
        .contains("*종목 (3) · KOSPI*")
        .contains("005930").contains("삼성전자").contains("SK하이닉스").contains("회피").contains("매수").contains("0.72")
        .contains("> *1 삼성전자(005930)* 외인 5일 순매수 상위, 52주 고점 -2%\n> ⚠ 환율 급등 시 수급 반전")
        .contains("> *2 SK하이닉스(000660)* 수급 이탈")
        .contains("> *3 NAVER(035420)* " + thesis300 + "\n> ⚠ " + risk150)
        .contains("5일 적중 54.8%").contains("run=1284").contains("model=judge-x").contains("prompt=advice-v2").contains("in 6,180 / out 1,940")
        .contains("$0.0123").contains("수집 결손일").contains(DailyAdviceMessage.DISCLAIMER);
    List<String> noteSections = blocks.stream().map(DailyAdviceMessageTest::text).filter(t -> t.startsWith("> *")).toList();
    assertThat(noteSections).as("advice-v5: 픽마다 section 1개, 말줄임 없음").hasSize(3).allMatch(t -> !t.contains("…") && t.length() <= 3000);
    assertThat(blocks.getLast()).isInstanceOf(ContextBlock.class);
    assertThat(message.toAttachments()).hasSize(1);
    String table = message.pickTable();
    assertThat(table).startsWith("순위").contains("삼성전자").contains("005930").doesNotContain("```").doesNotContain("반도체");
    List<String> rows = table.lines().toList();
    assertThat(rows).hasSize(4);
    assertThat(rows.get(1)).isEqualTo(" 1   005930 삼성전자      매수 0.80 0.72");
    assertThat(rows.get(2)).isEqualTo(" 2   000660 SK하이닉스    회피 0.60 0.68");
    assertThat(rows).as("한글 2칸 폭을 계산해 모든 행의 표시 폭이 같다").allMatch(r -> SlackWidth.width(r) == SlackWidth.width(rows.get(1)));
    assertThat(SlackWidth.width(rows.get(1))).isLessThanOrEqualTo(40);
    assertThat(all).contains("```");
  }

  @Test
  @DisplayName("v1 헤더(추세·전망·기준일 없음)도 그대로 렌더링된다 — 해당 줄만 생략")
  void rendersLegacyHeader() {
    AdviceHeader header = AdviceHeader.builder().adviceId(1L).runId(1L).baseDate(LocalDate.of(2026, 9, 11)).adviceKind("DAILY")
        .variant(AdviceVariant.LIVE).horizonDays(5).regimeCode(MarketRegimeCode.NEUTRAL).promptVersion("advice-v1").model("m").build();
    DailyAdviceMessage message = DailyAdviceMessage.builder().header(header).picks(List.of()).candidates(Map.of()).scoreboardLines(List.of())
        .runId(1L).build();
    String all = message.toBlocks().stream().map(DailyAdviceMessageTest::text).reduce("", String::concat);
    assertThat(all).doesNotContain("*추세*").doesNotContain("*추세 전망*").doesNotContain("*적용 구간*").contains("*데이터 기준*  국내 09-11 종가")
        .contains("*종목 (0)*");
  }

  @Test
  @DisplayName("픽 10개 × 가드 상한(thesis 400·risk 300)이어도 section 마다 3,000자·메시지 50블록 안이고, 본문 줄바꿈은 인용을 끊지 않는다")
  void fullTextNotesStayWithinBlockKitLimits() {
    AdviceHeader header = AdviceHeader.builder().adviceId(1L).runId(1L).baseDate(LocalDate.of(2026, 9, 11)).adviceKind("DAILY")
        .variant(AdviceVariant.LIVE).horizonDays(5).regimeCode(MarketRegimeCode.NEUTRAL).promptVersion("advice-v5").model("m").build();
    List<PickRow> picks = new java.util.ArrayList<>();
    Map<String, CandidateRow> candidates = new java.util.HashMap<>();
    for (int i = 1; i <= 10; i++) {
      String ticker = String.format("%06d", i);
      picks.add(PickRow.builder().ticker(ticker).pickRank(i).direction(PickDirection.LONG).conviction(0.7)
          .thesis("근".repeat(200) + "\n" + "거".repeat(199)).riskNote("위".repeat(300)).build());
      candidates.put(ticker, CandidateRow.builder().ticker(ticker).stockName("종목" + i).quantScore(0.5).build());
    }
    DailyAdviceMessage message = DailyAdviceMessage.builder().header(header).picks(picks).candidates(candidates).scoreboardLines(List.of())
        .runId(1L).build();
    List<LayoutBlock> blocks = message.toBlocks();
    assertThat(blocks.size()).isLessThanOrEqualTo(50);
    List<String> notes = blocks.stream().map(DailyAdviceMessageTest::text).filter(t -> t.startsWith("> *")).toList();
    assertThat(notes).hasSize(10).allMatch(t -> t.length() <= 3000).allMatch(t -> !t.contains("…"));
    assertThat(notes.getFirst()).startsWith("> *1 종목1(000001)* " + "근".repeat(200) + "\n> " + "거".repeat(199) + "\n> ⚠ " + "위".repeat(300));
    assertThat(DailyAdviceMessage.quoted(" 첫 줄\r\n둘째 줄 ")).isEqualTo("첫 줄\n> 둘째 줄");
    assertThat(DailyAdviceMessage.quoted(null)).isEmpty();
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
