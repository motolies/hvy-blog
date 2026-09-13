package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 가드 규칙을 고정한다: 후보 밖·중복·근거 위조 제거, 확신 이산화, AVOID 상한, 픽 상한·하한, 인젝션 문자 제거.
 */
class AdviceGuardTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final AdviceGuard guard = new AdviceGuard(properties);
  private final Map<String, String> sectors = Map.of("G2510", "반도체", "G3020", "음식료");

  @Test
  @DisplayName("후보 밖 티커·중복·모르는 방향·근거 위조는 제거되고 통계에 남는다")
  void removesInvalidPicks() {
    List<CandidateRow> candidates = List.of(candidate("005930", 0.081), candidate("000660", 0.05), candidate("035420", 0.02));
    AdviceResponse response = new AdviceResponse(
        new AdviceResponse.Regime("RISK_ON", "UP", "NEUTRAL", "0.70", "근거"),
        List.of(new AdviceResponse.SectorView("G2510", "반도체 강세"), new AdviceResponse.SectorView("XXXX", "없는 섹터")),
        List.of(
            pick("005930", "LONG", "0.80", List.of(new AdviceResponse.Cited("r20", 0.081))),
            pick("999999", "LONG", "0.70", List.of()),                                   // 후보 밖
            pick("005930", "LONG", "0.60", List.of()),                                   // 중복
            pick("000660", "SHORT", "0.60", List.of()),                                  // 모르는 방향
            pick("035420", "LONG", "0.65", List.of(new AdviceResponse.Cited("r20", 0.5)))), // 근거 위조 (0.02 vs 0.5)
        "총평 <!channel> 입니다");

    AdviceGuard.Result result = guard.validate(response, candidates, sectors);

    assertThat(result.picks()).extracting(PickRow::ticker).containsExactly("005930");
    assertThat(result.picks().getFirst().pickRank()).isEqualTo(1);
    assertThat(result.picks().getFirst().conviction()).isEqualTo(0.80);
    assertThat(result.originalPicks()).isEqualTo(5);
    assertThat(result.removed()).isEqualTo(4);
    assertThat(result.removalRatio()).isEqualTo(0.8);
    assertThat(result.stats()).containsEntry("unknownTicker", 1).containsEntry("duplicate", 1).containsEntry("badDirection", 1)
        .containsEntry("citedMismatch", 1).containsEntry("unknownSector", 1).containsEntry("sanitized", 1);
    assertThat(result.sectors()).hasSize(1);
    assertThat(result.sectors().getFirst().name()).isEqualTo("반도체");
    assertThat(result.summary()).isEqualTo("총평  입니다");
    assertThat(result.regime()).isEqualTo(MarketRegimeCode.RISK_ON);
    assertThat(result.kospiDir()).isEqualTo(DirectionCall.UP);
    assertThat(result.pUp()).isEqualTo(0.70);
    assertThat(result.tooFew(properties.getPickMin())).isTrue();
  }

  @Test
  @DisplayName("확신값은 허용 목록 밖이면 가장 가까운 값으로, 픽은 확신 내림차순 최대 10개, AVOID 는 2개까지")
  void clampsAndTruncates() {
    List<CandidateRow> candidates = new java.util.ArrayList<>();
    List<AdviceResponse.Pick> picks = new java.util.ArrayList<>();
    for (int i = 0; i < 14; i++) {
      String ticker = String.format("%06d", i);
      candidates.add(candidate(ticker, 0.01 * i));
      String direction = i < 4 ? "AVOID" : "LONG";
      picks.add(pick(ticker, direction, i == 0 ? "0.93" : String.format("%.2f", 0.55 + 0.02 * i), List.of()));
    }
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("neutral", "down", "up", "0.5", null), List.of(), picks, "요약");

    AdviceGuard.Result result = guard.validate(response, candidates, sectors);

    assertThat(result.picks()).hasSize(10);
    assertThat(result.picks().stream().filter(p -> p.direction() == PickDirection.AVOID).count()).isLessThanOrEqualTo(2);
    for (int i = 1; i < result.picks().size(); i++) {
      assertThat(result.picks().get(i).conviction()).isLessThanOrEqualTo(result.picks().get(i - 1).conviction());
      assertThat(result.picks().get(i).pickRank()).isEqualTo(i + 1);
    }
    assertThat(result.picks()).allMatch(p -> AdviceSchemaFactory.CONVICTIONS.contains(String.format("%.2f", p.conviction())));
    assertThat(result.stats()).containsKey("truncated").containsEntry("truncatedAvoid", 2);
    assertThat(result.regime()).isEqualTo(MarketRegimeCode.NEUTRAL);
    assertThat(result.kospiDir()).isEqualTo(DirectionCall.DOWN);
    assertThat(result.pUp()).as("0.5 → 가장 가까운 0.55").isEqualTo(0.55);
    assertThat(result.stats()).containsKey("clampedPUp");
    assertThat(result.tooFew(properties.getPickMin())).isFalse();
  }

  @Test
  @DisplayName("시장·섹터 특징 인용은 종목 특징이 아니라 검증 없이 기록만 하고, 열 별칭(distHigh·frgnFlow)은 대조된다")
  void citedAliasesAndNonCandidateNames() {
    CandidateRow c = candidate("005930", 0.081);
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("RISK_ON", "UP", "UP", "0.60", ""), List.of(),
        List.of(pick("005930", "LONG", "0.60", List.of(
            new AdviceResponse.Cited("distHigh", -0.02), new AdviceResponse.Cited("frgnFlow", 0.0031), new AdviceResponse.Cited("kospiR5", 0.01)))),
        "");
    AdviceGuard.Result ok = guard.validate(response, List.of(c), sectors);
    assertThat(ok.picks()).hasSize(1);
    assertThat(ok.picks().getFirst().cited()).hasSize(3);

    AdviceResponse bad = new AdviceResponse(response.regime(), List.of(),
        List.of(pick("005930", "LONG", "0.60", List.of(new AdviceResponse.Cited("frgnFlow", 0.03)))), "");
    assertThat(guard.validate(bad, List.of(c), sectors).picks()).isEmpty();
  }

  private static CandidateRow candidate(String ticker, double r20) {
    return CandidateRow.builder().ticker(ticker).quantRank(1).quantScore(0.5).stockName("n").marketType("KOSPI").benchIndexCode("0001")
        .sectorCode("G2510").sectorName("반도체")
        .signals(Map.of("FOREIGN_FLOW", new SignalValue(0.9, 0.12, 0.0031), "MOM_20D", new SignalValue(0.8, 0.12, r20)))
        .features(Map.of("r20", r20, "distHigh52w", -0.02, "per", 14.2)).appliedLessonIds(List.of()).build();
  }

  private static AdviceResponse.Pick pick(String ticker, String direction, String conviction, List<AdviceResponse.Cited> cited) {
    return new AdviceResponse.Pick(ticker, direction, conviction, "근거", "리스크", cited);
  }
}
