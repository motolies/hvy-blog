package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 가드 규칙을 고정한다: 후보 밖·중복·근거 위조 제거, 확신 이산화, AVOID 상한, 픽 상한·하한, 인젝션 문자 제거, 추세 전망 폴백·모순 강등,
 * advice-v6 섹터 맥락(주도 섹터 consistent 전달·secCons=0/overheated LONG 확신 클램프).
 */
class AdviceGuardTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final AdviceGuard guard = new AdviceGuard(properties);
  /** G2510 은 세 구간 연속 초과(consistent), G3020 은 과열(overheated) */
  private final AdviceGuard.SectorContext sectors = new AdviceGuard.SectorContext(Map.of("G2510", "반도체", "G3020", "음식료"), Set.of("G2510"), Set.of("G3020"));
  private final Map<String, MarketTrendCode> trends = Map.of("0001", MarketTrendCode.BULL, "1001", MarketTrendCode.BEAR);
  private final AdviceResponse.TrendOutlookView outlook = new AdviceResponse.TrendOutlookView(
      new AdviceResponse.Outlook("BEYOND_20D", "0.70", "BELOW_MA20"), new AdviceResponse.Outlook("WITHIN_5D", "0.60", "ABOVE_MA60"));

  @Test
  @DisplayName("후보 밖 티커·중복·모르는 방향·근거 위조는 제거되고 통계에 남는다")
  void removesInvalidPicks() {
    List<CandidateRow> candidates = List.of(candidate("005930", 0.081), candidate("000660", 0.05), candidate("035420", 0.02));
    AdviceResponse response = new AdviceResponse(
        new AdviceResponse.Regime("RISK_ON", "UP", "NEUTRAL", "0.70", "근거"), outlook,
        List.of(new AdviceResponse.SectorView("G2510", "반도체 강세"), new AdviceResponse.SectorView("XXXX", "없는 섹터")),
        List.of(
            pick("005930", "LONG", "0.80", List.of(new AdviceResponse.Cited("r20", 0.081))),
            pick("999999", "LONG", "0.70", List.of()),                                   // 후보 밖
            pick("005930", "LONG", "0.60", List.of()),                                   // 중복
            pick("000660", "SHORT", "0.60", List.of()),                                  // 모르는 방향
            pick("035420", "LONG", "0.65", List.of(new AdviceResponse.Cited("r20", 0.5)))), // 근거 위조 (0.02 vs 0.5)
        "총평 <!channel> 입니다");

    AdviceGuard.Result result = guard.validate(response, candidates, sectors, trends);

    assertThat(result.picks()).extracting(PickRow::ticker).containsExactly("005930");
    assertThat(result.picks().getFirst().pickRank()).isEqualTo(1);
    assertThat(result.picks().getFirst().conviction()).as("secCons null(업종 지수 없음)·비과열 섹터는 클램프 대상이 아니다").isEqualTo(0.80);
    assertThat(result.originalPicks()).isEqualTo(5);
    assertThat(result.removed()).isEqualTo(4);
    assertThat(result.removalRatio()).isEqualTo(0.8);
    assertThat(result.stats()).containsEntry("unknownTicker", 1).containsEntry("duplicate", 1).containsEntry("badDirection", 1)
        .containsEntry("citedMismatch", 1).containsEntry("unknownSector", 1).containsEntry("sanitized", 1)
        .doesNotContainKeys("capNonConsistent", "capOverheated");
    assertThat(result.sectors()).hasSize(1);
    assertThat(result.sectors().getFirst().name()).isEqualTo("반도체");
    assertThat(result.sectors().getFirst().consistent()).as("advice-v6: 주도 섹터 콜에 입력 맥락의 consistent 를 채운다").isTrue();
    assertThat(result.summary()).isEqualTo("총평  입니다");
    assertThat(result.regime()).isEqualTo(MarketRegimeCode.RISK_ON);
    assertThat(result.kospiDir()).isEqualTo(DirectionCall.UP);
    assertThat(result.pUp()).isEqualTo(0.70);
    assertThat(result.tooFew(properties.getPickMin())).isTrue();
    assertThat(result.outlooks()).extracting(TrendOutlook::indexCode).containsExactly("0001", "1001");
    assertThat(result.outlooks().getFirst()).isEqualTo(new TrendOutlook("0001", TrendHorizon.BEYOND_20D, 0.70, InvalidationType.BELOW_MA20));
    assertThat(result.outlooks().get(1)).as("약세장의 상향 돌파 조건은 방향이 맞다")
        .isEqualTo(new TrendOutlook("1001", TrendHorizon.WITHIN_5D, 0.60, InvalidationType.ABOVE_MA60));
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
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("neutral", "down", "up", "0.5", null), outlook, List.of(), picks, "요약");

    AdviceGuard.Result result = guard.validate(response, candidates, sectors, trends);

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
  @DisplayName("advice-v6: secCons=0 후보·overheated 섹터의 LONG 픽은 확신이 0.70 으로 내려가고(제거 아님) capNonConsistent/capOverheated 에 남는다 — "
      + "AVOID·상한 이하·secCons null 은 그대로, 주도 섹터 콜은 consistent 를 담는다")
  void capsNonConsistentAndOverheatedLongPicks() {
    List<CandidateRow> candidates = List.of(
        sectorCandidate("000001", "G2510", 0.05),   // secCons 1, 비과열 → 그대로
        sectorCandidate("000002", "G2510", -0.02),  // secCons 0 → 클램프
        sectorCandidate("000003", "G3020", 0.05),   // secCons 1 이지만 과열 섹터 → 클램프
        sectorCandidate("000004", "G2510", -0.02),  // secCons 0 이지만 AVOID → 대상 아님
        sectorCandidate("000005", "G2510", -0.02),  // secCons 0 이지만 0.65 ≤ 상한 → 통계도 남지 않음
        sectorCandidate("000006", "G2510", null));  // secRs60 없음 → secCons null → 그대로
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("RISK_ON", "UP", "UP", "0.60", ""), outlook,
        List.of(new AdviceResponse.SectorView("G2510", "지속"), new AdviceResponse.SectorView("G3020", "3구간 초과 미충족")),
        List.of(pick("000001", "LONG", "0.85", List.of()), pick("000002", "LONG", "0.85", List.of()), pick("000003", "LONG", "0.80", List.of()),
            pick("000004", "AVOID", "0.85", List.of()), pick("000005", "LONG", "0.65", List.of()), pick("000006", "LONG", "0.90", List.of())),
        "");

    AdviceGuard.Result result = guard.validate(response, candidates, sectors, trends);

    assertThat(result.removed()).isZero();
    assertThat(result.picks()).hasSize(6);
    Map<String, Double> conviction = new HashMap<>();
    result.picks().forEach(p -> conviction.put(p.ticker(), p.conviction()));
    assertThat(conviction).containsEntry("000001", 0.85).containsEntry("000002", 0.70).containsEntry("000003", 0.70)
        .containsEntry("000004", 0.85).containsEntry("000005", 0.65).containsEntry("000006", 0.90);
    assertThat(result.stats()).containsEntry("capNonConsistent", 1).containsEntry("capOverheated", 1).doesNotContainKey("clampedConviction");
    assertThat(result.picks().getFirst().ticker()).as("클램프 뒤 확신 내림차순으로 순위가 매겨진다").isEqualTo("000006");
    assertThat(result.sectors()).extracting(SectorCall::code, SectorCall::consistent)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("G2510", true), org.assertj.core.groups.Tuple.tuple("G3020", false));

    // 맥락이 이름만이면 과열 클램프·consistent 는 없고, secCons 클램프(후보 특징 기반)만 남는다
    AdviceGuard.Result plain = guard.validate(response, candidates, AdviceGuard.SectorContext.ofNames(Map.of("G2510", "반도체", "G3020", "음식료")), trends);
    assertThat(plain.picks().stream().filter(p -> p.ticker().equals("000003")).findFirst().orElseThrow().conviction()).isEqualTo(0.80);
    assertThat(plain.stats()).doesNotContainKey("capOverheated").containsEntry("capNonConsistent", 1);
    assertThat(plain.sectors()).allMatch(s -> Boolean.FALSE.equals(s.consistent()));

    // 상한은 설정에서 온다 (가드는 호출 시점의 properties 를 읽는다)
    properties.getAdvise().setNonConsistentConvictionCap(0.60);
    AdviceGuard.Result lower = guard.validate(response, candidates, sectors, trends);
    assertThat(lower.picks().stream().filter(p -> p.ticker().equals("000002")).findFirst().orElseThrow().conviction()).isEqualTo(0.60);
    assertThat(lower.picks().stream().filter(p -> p.ticker().equals("000005")).findFirst().orElseThrow().conviction()).as("0.65 > 0.60 이라 이제 클램프").isEqualTo(0.60);
    assertThat(lower.stats()).containsEntry("capNonConsistent", 2).containsEntry("capOverheated", 1);
  }

  @Test
  @DisplayName("시장·섹터 특징 인용은 종목 특징이 아니라 검증 없이 기록만 하고, 열 별칭(distHigh·frgnFlow)은 대조된다")
  void citedAliasesAndNonCandidateNames() {
    CandidateRow c = candidate("005930", 0.081);
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("RISK_ON", "UP", "UP", "0.60", ""), outlook, List.of(),
        List.of(pick("005930", "LONG", "0.60", List.of(
            new AdviceResponse.Cited("distHigh", -0.02), new AdviceResponse.Cited("frgnFlow", 0.0031), new AdviceResponse.Cited("kospiR5", 0.01)))),
        "");
    AdviceGuard.Result ok = guard.validate(response, List.of(c), sectors, trends);
    assertThat(ok.picks()).hasSize(1);
    assertThat(ok.picks().getFirst().cited()).hasSize(3);

    AdviceResponse bad = new AdviceResponse(response.regime(), outlook, List.of(),
        List.of(pick("005930", "LONG", "0.60", List.of(new AdviceResponse.Cited("frgnFlow", 0.03)))), "");
    assertThat(guard.validate(bad, List.of(c), sectors, trends).picks()).isEmpty();
  }

  @Test
  @DisplayName("추세 전망: enum 밖은 ABOUT_20D/NONE 폴백, 추세 방향과 모순된 무효화는 NONE 강등, 블록 자체가 없으면 기본값 — 픽 제거율엔 영향 없음")
  void outlookFallbacks() {
    CandidateRow c = candidate("005930", 0.081);
    AdviceResponse.TrendOutlookView odd = new AdviceResponse.TrendOutlookView(
        new AdviceResponse.Outlook("FOREVER", "0.72", "ABOVE_MA20"),   // 강세장에 상향 돌파 = 모순
        new AdviceResponse.Outlook("about_20d", null, "sideways"));
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("RISK_ON", "UP", "UP", "0.60", ""), odd, List.of(),
        List.of(pick("005930", "LONG", "0.60", List.of())), "");

    AdviceGuard.Result result = guard.validate(response, List.of(c), sectors, trends);

    assertThat(result.picks()).hasSize(1);
    assertThat(result.removed()).isZero();
    assertThat(result.outlooks().getFirst()).isEqualTo(new TrendOutlook("0001", TrendHorizon.ABOUT_20D, 0.70, InvalidationType.NONE));
    assertThat(result.outlooks().get(1)).isEqualTo(new TrendOutlook("1001", TrendHorizon.ABOUT_20D, 0.55, InvalidationType.NONE));
    assertThat(result.stats()).containsEntry("badPersist", 1).containsEntry("inconsistentInvalidation", 1).containsEntry("badInvalidation", 1)
        .containsEntry("clampedOutlookConfidence", 2);

    AdviceResponse none = new AdviceResponse(response.regime(), null, List.of(), List.of(pick("005930", "LONG", "0.60", List.of())), "");
    AdviceGuard.Result missing = guard.validate(none, List.of(c), sectors, Map.of());
    assertThat(missing.outlooks()).hasSize(2).allMatch(o -> o.persist() == TrendHorizon.ABOUT_20D && o.invalidation() == InvalidationType.NONE);
    assertThat(missing.stats()).containsEntry("missingOutlook", 2).doesNotContainKey("badPersist");
  }

  @Test
  @DisplayName("인용 헤드라인: 입력에 없던 id 제거(unknownNews), 다른 종목에만 태깅된 기사 제거(newsMismatch), 시장 헤드라인은 허용, 뉴스 블록이 없으면 전부 제거")
  void citedNewsFiltering() {
    kr.hvy.blog.modules.advisor.domain.model.NewsBlock news = new kr.hvy.blog.modules.advisor.domain.model.NewsBlock(java.time.Instant.now(), 36,
        List.of(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N1", "09-11 16:20", "시장", List.of())),
        Map.of("005930", List.of(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N2", "09-11 08:40", "삼성", List.of("005930"))),
            "000660", List.of(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N3", "09-11 09:00", "하이닉스", List.of("000660")))));
    List<CandidateRow> candidates = List.of(candidate("005930", 0.081), candidate("000660", 0.05));
    AdviceResponse response = new AdviceResponse(new AdviceResponse.Regime("RISK_ON", "UP", "UP", "0.60", ""), outlook, List.of(),
        List.of(new AdviceResponse.Pick("005930", "LONG", "0.60", "근거", "리스크", List.of(), List.of("N1", "N2", "N3", "N2", "N7")),
            new AdviceResponse.Pick("000660", "LONG", "0.60", "근거", "리스크", List.of(), null)), "");

    AdviceGuard.Result result = guard.validate(response, candidates, sectors, trends, news);
    assertThat(result.picks()).hasSize(2);
    assertThat(result.picks().getFirst().citedNews()).containsExactly("N1", "N2");
    assertThat(result.picks().get(1).citedNews()).isEmpty();
    assertThat(result.stats()).containsEntry("newsMismatch", 1).containsEntry("unknownNews", 1);

    AdviceGuard.Result noNews = guard.validate(response, candidates, sectors, trends, null);
    assertThat(noNews.picks().getFirst().citedNews()).isEmpty();
    assertThat(noNews.stats()).containsEntry("unknownNews", 5);
  }

  private static CandidateRow candidate(String ticker, double r20) {
    return CandidateRow.builder().ticker(ticker).quantRank(1).quantScore(0.5).stockName("n").marketType("KOSPI").benchIndexCode("0001")
        .sectorCode("G2510").sectorName("반도체")
        .signals(Map.of("FOREIGN_FLOW", new SignalValue(0.9, 0.12, 0.0031), "MOM_20D", new SignalValue(0.8, 0.12, r20)))
        .features(Map.of("r20", r20, "distHigh52w", -0.02, "per", 14.2)).appliedLessonIds(List.of()).build();
  }

  /** 소속 섹터·섹터 3개월 초과(secRs60, null 이면 키 없음)를 가진 후보. secRs5·secRs20 은 양수 고정이라 secCons 는 secRs60 부호로 정해진다 */
  private static CandidateRow sectorCandidate(String ticker, String sector, Double secRs60) {
    Map<String, Object> features = new HashMap<>(Map.of("r20", 0.03, "secRs5", 0.01, "secRs20", 0.02));
    if (secRs60 != null) {
      features.put("secRs60", secRs60);
    }
    return CandidateRow.builder().ticker(ticker).quantRank(1).quantScore(0.5).stockName("n").marketType("KOSPI").benchIndexCode("0001")
        .sectorCode(sector).sectorName(sector).signals(Map.of()).features(features).appliedLessonIds(List.of()).build();
  }

  private static AdviceResponse.Pick pick(String ticker, String direction, String conviction, List<AdviceResponse.Cited> cited) {
    return new AdviceResponse.Pick(ticker, direction, conviction, "근거", "리스크", cited, null);
  }
}
