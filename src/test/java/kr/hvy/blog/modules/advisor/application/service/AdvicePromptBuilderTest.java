package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 입력 JSON 규약: null 생략·4자리 반올림·후보 표 형태·길이 상한 초과 시 후보 절단·스키마 enum 재료(티커·섹터) 제공,
 * advice-v2 의 dataAsOf·window·dataQuality·market.trend 블록.
 */
class AdvicePromptBuilderTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final AdvicePromptBuilder builder = new AdvicePromptBuilder(properties);

  @Test
  @DisplayName("JSON 에는 market·sectors·candidates(columns/rows)·weights 가 있고 null 은 빠지며 실수는 4자리다")
  void buildsCompactJson() {
    PromptPayload payload = builder.build(market(), screening(3), null, List.of(), Map.of("MOM_20D", 0.123456789), DataQuality.OK);
    String json = payload.json();
    assertThat(json).startsWith("{\"asOf\":\"2026-09-11\",\"horizonDays\":5,\"market\":{\"index\":[");
    assertThat(json).contains("\"columns\":[\"tkr\",\"name\",\"sec\",\"score\",\"r20\"");
    assertThat(json).contains("[\"T00\",\"종목0\",\"G2510\",0.5,0.0123,");
    assertThat(json).contains("\"weights\":{\"MOM_20D\":0.1235}");
    assertThat(json).as("객체·Map 의 null 값은 생략(지수 r60 없음)").doesNotContain("\"r60\":null").doesNotContain("\"indi5\":null")
        .doesNotContain("scoreboard").doesNotContain("lessons");
    assertThat(json).as("표 행의 빈 열은 위치 보존을 위해 null 유지(instFlow 없음)").contains(",0.0031,null,0.043,14.23,");
    assertThat(json).contains("\"sigma5d\":{\"0001\":0.0102}");
    assertThat(payload.candidateTickers()).containsExactly("T00", "T01", "T02");
    assertThat(payload.sectorCodes()).as("advice-v6: 주도 섹터 enum 은 후보가 있는 섹터만 — bottom 의 G3020 은 빠진다").containsExactly("G2510");
    assertThat(payload.truncated()).isFalse();
    assertThat(payload.estimatedTokens()).isEqualTo(json.length() / 3);

    // advice-v6: 새 열은 맨 뒤 — 섹터 rs5/rs20/rs60/mom/consistent/overheated, 후보 secRs60/secCons(1·0·null)
    assertThat(json).contains("\"sectors\":{\"columns\":[\"code\",\"name\",\"cw5\",\"rising\",\"nearHigh\",\"frgn5\",\"members\",\"rs5\",\"rs20\",\"rs60\",\"mom\",\"consistent\",\"overheated\"],"
        + "\"top\":[[\"G2510\",\"반도체\",0.0412,0.71,0.34,286000000000,58,0.0123,0.0456,0.0789,0.9167,true,false]],"
        + "\"bottom\":[[\"G3020\",\"음식료\",-0.0231,0.19,0.03,-41000000000,42,null,null,null,null,false,false]]}");
    assertThat(json).contains("\"columns\":[\"tkr\",\"name\",\"sec\",\"score\",\"r20\",\"r60\",\"distHigh\",\"tvRatio\",\"frgnFlow\",\"instFlow\",\"rsIdx\",\"per\",\"pbr\",\"vol20\",\"secRs60\",\"secCons\"]");
    assertThat(json).as("T00: 세 구간 모두 양수 → secCons 1").contains(",14.23,null,0.0182,0.0789,1]");
    assertThat(json).as("T01: rs60 음수 → secCons 0").contains(",14.23,null,0.0182,-0.01,0]");
    assertThat(json).as("T02: 업종 지수 없음 → secRs60·secCons null").contains(",14.23,null,0.0182,null,null]");
  }

  @Test
  @DisplayName("advice-v2: market.trend(규칙 추세·기저율)·dataAsOf·window·dataQuality 가 market 뒤에 실린다")
  void includesTimeAxisBlocks() {
    String json = builder.build(market(), screening(2), null, List.of(), Map.of(), DataQuality.DEGRADED).json();
    assertThat(json).contains("\"trend\":[{\"index\":\"0001\",\"code\":\"BULL\",\"score\":4,\"components\":{\"ma20\":1,\"ma60\":1,\"ma120\":1,\"ret60\":1,\"breadth\":0},"
        + "\"since\":\"2026-07-28\",\"days\":32,\"close\":2731.44,\"ma20\":2612.4,\"ma60\":2540.1,\"ma120\":2488.7,\"breadth\":0.58,"
        + "\"base\":{\"episodes\":9,\"medianDays\":27.0,\"fwd5\":{\"n\":400,\"pUp\":0.61,\"mean\":0.006},\"fwd20\":{\"n\":380,\"pUp\":0.66,\"mean\":0.021}}}]");
    int marketEnd = json.indexOf("\"dataAsOf\"");
    assertThat(marketEnd).as("dataAsOf 는 market 뒤").isGreaterThan(json.indexOf("\"sigma5d\""));
    assertThat(json).contains("\"dataAsOf\":{\"domestic\":\"2026-09-11\",\"flow\":\"2026-09-11\",\"flowProvisional\":true,\"sector\":\"2026-09-11\","
        + "\"global\":\"2026-09-10\",\"globalAgeTradingDays\":1,\"sectorIndex\":\"2026-09-11\"}");
    assertThat(json).contains("\"window\":{\"entry\":\"2026-09-14\",\"exit\":\"2026-09-18\",\"entryRule\":\"다음 영업일 시가\",\"exitRule\":\"5번째 영업일 종가\"}");
    assertThat(json).contains("\"dataQuality\":\"DEGRADED\"");
    assertThat(json.indexOf("\"dataQuality\"")).isLessThan(json.indexOf("\"sectors\""));
    // advice-v3: 미국 r20/r60 과 연동 강도
    assertThat(json).contains("\"global\":[{\"sym\":\"SPX\",\"date\":\"2026-09-10\",\"close\":6500.5,\"r1\":0.0061,\"r5\":0.0154,\"r20\":0.0312,\"r60\":0.0871}]");
    assertThat(json).contains("\"link\":[{\"kr\":\"0001\",\"us\":\"SPX\",\"beta\":0.512,\"corr\":0.615,\"n\":60},{\"kr\":\"1001\",\"us\":\"COMP\",\"n\":0}]");
  }

  @Test
  @DisplayName("길이 상한을 넘으면 점수 낮은 후보부터 5개씩 잘라내고 truncated 를 표시한다 (추세 블록은 살아남는다)")
  void truncatesCandidatesToFitBudget() {
    properties.getPrompt().setMaxInputChars(2_400);
    PromptPayload payload = builder.build(market(), screening(30), null, List.of(), Map.of(), DataQuality.OK);
    assertThat(payload.truncated()).isTrue();
    assertThat(payload.candidatesIncluded()).isLessThan(30).isGreaterThanOrEqualTo(5);
    assertThat(payload.candidateTickers()).hasSize(payload.candidatesIncluded()).startsWith("T00");
    assertThat(payload.json()).contains("\"trend\":[").contains("\"window\":{");
  }

  @Test
  @DisplayName("스코어보드·교훈이 있으면 그대로 실린다 (교훈은 id·condition·text 만)")
  void includesScoreboardAndLessons() {
    kr.hvy.blog.modules.advisor.domain.model.LessonRow lesson = kr.hvy.blog.modules.advisor.domain.model.LessonRow.builder().lessonId(12L)
        .condition(Map.of("regime", "RISK_OFF")).lessonText("[관찰] ... [규칙] ...").observation("obs").rule("rule").build();
    PromptPayload payload = builder.build(market(), screening(2), Map.of("window", "20d", "hit5d", 0.55), List.of(lesson), Map.of(), null);
    assertThat(payload.json()).contains("\"scoreboard\":{").contains("\"lessons\":[{\"id\":12,\"condition\":{\"regime\":\"RISK_OFF\"},\"text\":\"[관찰] ... [규칙] ...\"}]");
    assertThat(payload.json()).doesNotContain("obs").doesNotContain("\"rule\"");
    assertThat(payload.json()).as("quality null 은 OK").contains("\"dataQuality\":\"OK\"");
  }

  @Test
  @DisplayName("note-v1: recentOutcomes 는 scoreboard 뒤·lessons 앞에 그대로 실리고, null·빈 맵이면 키 자체가 없다")
  void recentOutcomesPosition() {
    kr.hvy.blog.modules.advisor.domain.model.LessonRow lesson = kr.hvy.blog.modules.advisor.domain.model.LessonRow.builder().lessonId(12L)
        .condition(Map.of("regime", "RISK_OFF")).lessonText("[관찰] ... [규칙] ...").build();
    Map<String, Object> outcomes = new java.util.LinkedHashMap<>();
    outcomes.put("windowTradingDays", 20);
    outcomes.put("finalizedAsOf", "2026-10-10");
    outcomes.put("columns", List.of("class", "secCons", "n", "confirmRate", "meanFinalExcess", "se", "underpowered"));
    outcomes.put("rows", List.of(List.of("IDIOSYNCRATIC", 1, 14, 0.64, -0.0121, 0.0048, true)));

    String json = builder.build(market(), screening(2), Map.of("window", "20d"), List.of(lesson), Map.of(), DataQuality.OK, null, outcomes).json();
    assertThat(json).contains("\"recentOutcomes\":{\"windowTradingDays\":20,\"finalizedAsOf\":\"2026-10-10\",\"columns\":[\"class\",\"secCons\",\"n\","
        + "\"confirmRate\",\"meanFinalExcess\",\"se\",\"underpowered\"],\"rows\":[[\"IDIOSYNCRATIC\",1,14,0.64,-0.0121,0.0048,true]]}");
    assertThat(json.indexOf("\"recentOutcomes\"")).isGreaterThan(json.indexOf("\"scoreboard\"")).isLessThan(json.indexOf("\"lessons\""));

    assertThat(builder.build(market(), screening(2), null, List.of(), Map.of(), DataQuality.OK, null, null).json()).doesNotContain("recentOutcomes");
    assertThat(builder.build(market(), screening(2), null, List.of(), Map.of(), DataQuality.OK, null, Map.of()).json()).doesNotContain("recentOutcomes");
    assertThat(builder.build(market(), screening(2), null, List.of(), Map.of(), DataQuality.OK, null).json()).as("7인자 오버로드는 null 위임").doesNotContain("recentOutcomes");
  }

  @Test
  @DisplayName("advice-v4: news 블록은 candidates 뒤 표 형태로 실리고 newsIds 가 나오며, 자 상한을 넘으면 후보별 → 시장 순으로 줄인다(후보 행은 그대로)")
  void newsBlockAndTrimming() {
    List<kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline> market = new ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      market.add(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N" + i, "09-11 1" + (i % 10) + ":00", "시장 헤드라인 " + i + " — 반도체 수급과 환율 이야기", List.of()));
    }
    Map<String, List<kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline>> byTicker = new java.util.LinkedHashMap<>();
    for (int t = 0; t < 3; t++) {
      List<kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline> list = new ArrayList<>();
      for (int j = 0; j < 3; j++) {
        list.add(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N" + (13 + t * 3 + j), "09-11 09:0" + j, "종목" + t + " 기사 " + j + " 신제품 발표와 수주 소식", List.of(String.format("T%02d", t))));
      }
      byTicker.put(String.format("T%02d", t), list);
    }
    kr.hvy.blog.modules.advisor.domain.model.NewsBlock news = new kr.hvy.blog.modules.advisor.domain.model.NewsBlock(java.time.Instant.parse("2026-09-11T10:30:00Z"), 36, market, byTicker);

    PromptPayload full = builder.build(market(), screening(3), null, List.of(), Map.of(), DataQuality.OK, news);
    assertThat(full.newsIds()).hasSize(21).startsWith("N1").endsWith("N21");
    String json = full.json();
    assertThat(json).contains("\"news\":{\"asOf\":\"2026-09-11T10:30:00Z\",\"windowHours\":36,\"columns\":[\"id\",\"time\",\"title\"],\"market\":[[\"N1\",");
    assertThat(json.indexOf("\"news\"")).isGreaterThan(json.indexOf("\"candidates\"")).isLessThan(json.indexOf("\"weights\""));
    assertThat(json).contains("\"byTicker\":{\"T00\":[[\"N13\",\"09-11 09:00\",\"종목0 기사 0 신제품 발표와 수주 소식\"]");

    properties.getNews().setMaxChars(700);
    PromptPayload trimmed = builder.build(market(), screening(3), null, List.of(), Map.of(), DataQuality.OK, news);
    assertThat(trimmed.newsIds().size()).isLessThan(21).isGreaterThan(0);
    assertThat(trimmed.candidatesIncluded()).as("뉴스가 후보를 밀어내지 않는다").isEqualTo(3);
    assertThat(trimmed.truncated()).isFalse();
    assertThat(builder.fitNews(news).market()).hasSizeLessThanOrEqualTo(6);

    properties.getNews().setMaxChars(10);
    PromptPayload none = builder.build(market(), screening(3), null, List.of(), Map.of(), DataQuality.OK, news);
    assertThat(none.newsIds()).isEmpty();
    assertThat(none.json()).doesNotContain("\"news\"");
    assertThat(builder.build(market(), screening(3), null, List.of(), Map.of(), DataQuality.OK, null).newsIds()).isEmpty();
  }

  /** 반도체(G2510)는 세 구간 연속 초과(consistent), 음식료(G3020)는 업종 지수 없음(rs·mom null, consistent false) */
  static MarketFeatures market() {
    return new MarketFeatures(LocalDate.of(2026, 9, 11),
        List.of(new MarketFeatures.IndexFeature("0001", "KOSPI", 2731.44, 0.0042, -0.0113, 0.0287, null, 0.0129, 0.0402)),
        List.of(new MarketFeatures.FlowFeature("KOSPI", -182000000000L, 94000000000L, 88000000000L, -410000000000L, 221000000000L, null)),
        List.of(new MarketFeatures.GlobalFeature("SPX", LocalDate.of(2026, 9, 10), 6500.5, 0.0061, 0.0154, 0.0312, 0.0871)),
        List.of(new MarketFeatures.SectorFeature("G2510", "반도체", 0.0412, 0.71, 0.34, 286000000000L, 58, 0.012345, 0.0456, 0.0789, 0.91667, true, false)),
        List.of(new MarketFeatures.SectorFeature("G3020", "음식료", -0.0231, 0.19, 0.03, -41000000000L, 42, null, null, null, null, false, false)),
        Map.of("0001", 0.01023), LocalDate.of(2026, 9, 10), 1, LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11),
        LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18), List.of(trend()),
        List.of(new kr.hvy.blog.modules.advisor.domain.model.GlobalLink("0001", "SPX", 0.5123, 0.6149, 60),
            new kr.hvy.blog.modules.advisor.domain.model.GlobalLink("1001", "COMP", null, null, 0)),
        LocalDate.of(2026, 9, 11));
  }

  static MarketTrend trend() {
    java.util.LinkedHashMap<String, Integer> components = new java.util.LinkedHashMap<>();
    components.put("ma20", 1);
    components.put("ma60", 1);
    components.put("ma120", 1);
    components.put("ret60", 1);
    components.put("breadth", 0);
    return MarketTrend.builder().indexCode("0001").tradeDate(LocalDate.of(2026, 9, 11)).code(MarketTrendCode.BULL).rawCode(MarketTrendCode.BULL)
        .score(4).components(components).since(LocalDate.of(2026, 7, 28)).days(32).close(2731.44).ma20(2612.4).ma60(2540.1).ma120(2488.7).breadth(0.58)
        .base(new MarketTrend.Base(9, 27.0, new MarketTrend.Forward(400, 0.61, 0.006), new MarketTrend.Forward(380, 0.66, 0.021))).build();
  }

  /** 후보 n개(전부 G2510). 섹터 초과(advice-v6): T00 은 세 구간 양수(secCons 1), T01 은 rs60 음수(0), 나머지는 업종 지수 없음(null) */
  static ScreeningResult screening(int n) {
    List<CandidateRow> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Map<String, Object> features = new java.util.LinkedHashMap<>(Map.of("r20", 0.012345, "r60", 0.17, "distHigh52w", -0.021, "tvRatio", 1.62,
          "per", 14.234, "vol20d", 0.0182));
      if (i <= 1) {
        features.put("secRs5", 0.0123);
        features.put("secRs20", 0.0456);
        features.put("secRs60", i == 0 ? 0.0789 : -0.01);
      }
      rows.add(CandidateRow.builder().ticker(String.format("T%02d", i)).quantRank(i + 1).quantScore(0.5 - i * 0.01).stockName("종목" + i)
          .marketType("KOSPI").benchIndexCode("0001").sectorCode("G2510").sectorName("반도체")
          .signals(Map.of("FOREIGN_FLOW", new SignalValue(0.9, 0.12, 0.0031), "RS_INDEX", new SignalValue(0.8, 0.08, 0.043)))
          .features(features)
          .appliedLessonIds(List.of()).build());
    }
    return new ScreeningResult(LocalDate.of(2026, 9, 11), 1200, 300, 1L, rows);
  }
}
