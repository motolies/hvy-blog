package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.MorningAdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 아침 재판정 가드: KEEP/DROP 은 저녁 픽에만, ADD 는 저녁 후보 안·저녁 픽 밖만, 결정 누락은 KEEP 보충, 하한 미달은 DROP 되돌림, 상한·AVOID 초과는 ADD 부터 제거.
 */
class MorningAdviceGuardTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final MorningAdviceGuard guard = new MorningAdviceGuard(properties);

  private final List<PickRow> evening = List.of(
      pick("000001", 1, PickDirection.LONG, 0.80),
      pick("000002", 2, PickDirection.LONG, 0.70),
      pick("000003", 3, PickDirection.LONG, 0.65),
      pick("000004", 4, PickDirection.AVOID, 0.60));
  private final List<CandidateRow> candidates = List.of(cand("000001"), cand("000002"), cand("000003"), cand("000004"), cand("000005"), cand("000006"));

  @Test
  @DisplayName("후보 밖 ADD·저녁 픽과 겹치는 ADD 는 제거되고, 저녁 픽에 없는 종목의 DROP 은 무시된다 — 위반은 stats·violations 에 남는다")
  void rangeViolationsRemoved() {
    MorningAdviceResponse response = new MorningAdviceResponse(
        List.of(decision("000001", "KEEP", "유지 사유"), decision("000002", "DROP", "SOXX z=-3 로 thesis 붕괴"), decision("000003", "KEEP", "r"),
            decision("000004", "KEEP", "r"), decision("999999", "DROP", "저녁 픽 아님"), decision("000005", "DROP", "후보지만 픽 아님")),
        List.of(addition("000005", "LONG", "0.60", "추가 사유"), addition("777777", "LONG", "0.70", "후보 밖"), addition("000001", "LONG", "0.70", "이미 픽")),
        "요약");

    MorningAdviceGuard.Result r = guard.validate(response, evening, candidates);

    assertThat(r.kept()).extracting(PickRow::ticker).containsExactlyInAnyOrder("000001", "000003", "000004");
    assertThat(r.added()).extracting(PickRow::ticker).containsExactly("000005");
    assertThat(r.drops()).extracting(d -> d.evening().ticker()).containsExactly("000002");
    assertThat(r.drops().getFirst().reason()).isEqualTo("SOXX z=-3 로 thesis 붕괴");
    assertThat(r.picks()).extracting(PickRow::ticker).doesNotContain("777777", "999999", "000002");
    assertThat(r.stats()).containsEntry("unknownDecision", 2).containsEntry("addOutsideCandidates", 1).containsEntry("addAlreadyPicked", 1)
        .containsEntry("keep", 3).containsEntry("add", 1).containsEntry("drop", 1);
    assertThat(r.violations()).contains(Map.of("ticker", "777777", "rule", "addOutsideCandidates"), Map.of("ticker", "999999", "rule", "unknownDecision"));
  }

  @Test
  @DisplayName("KEEP 은 저녁 픽의 방향·확신·근거를 잇고 action·사유를 채운다, ADD 확신은 이산값으로 스냅되고 순위는 확신 내림차순으로 다시 매긴다")
  void keepCarriesEveningPick() {
    MorningAdviceResponse response = new MorningAdviceResponse(
        List.of(decision("000001", "KEEP", "a"), decision("000002", "KEEP", "b"), decision("000003", "KEEP", "c"), decision("000004", "KEEP", "d")),
        List.of(addition("000006", "LONG", "0.77", "e")), null);

    MorningAdviceGuard.Result r = guard.validate(response, evening, candidates);

    PickRow kept = r.picks().stream().filter(p -> p.ticker().equals("000001")).findFirst().orElseThrow();
    assertThat(kept.action()).isEqualTo(PickAction.KEEP);
    assertThat(kept.actionReason()).isEqualTo("a");
    assertThat(kept.thesis()).isEqualTo("thesis-000001");
    assertThat(kept.conviction()).isEqualTo(0.80);
    PickRow added = r.added().getFirst();
    assertThat(added.action()).isEqualTo(PickAction.ADD);
    assertThat(added.conviction()).isEqualTo(0.75);
    assertThat(r.picks()).extracting(PickRow::pickRank).containsExactly(1, 2, 3, 4, 5);
    assertThat(r.picks()).extracting(PickRow::ticker).containsExactly("000001", "000006", "000002", "000003", "000004");
  }

  @Test
  @DisplayName("결정이 빠진 저녁 픽은 KEEP 으로 보충되고, 픽 하한(3) 미달이면 DROP 을 저녁 순위대로 되돌린다")
  void missingDecisionAndMinimum() {
    MorningAdviceResponse response = new MorningAdviceResponse(
        List.of(decision("000001", "DROP", "x"), decision("000002", "DROP", "y"), decision("000003", "DROP", "z")), List.of(), "s");

    MorningAdviceGuard.Result r = guard.validate(response, evening, candidates);

    assertThat(r.stats()).containsEntry("missingDecision", 1).containsEntry("dropReverted", 2);
    assertThat(r.picks()).hasSize(3);
    assertThat(r.kept()).extracting(PickRow::ticker).containsExactlyInAnyOrder("000004", "000001", "000002");
    assertThat(r.drops()).extracting(d -> d.evening().ticker()).containsExactly("000003");
    assertThat(r.kept().stream().filter(p -> p.ticker().equals("000004")).findFirst().orElseThrow().actionReason())
        .isEqualTo(MorningAdviceGuard.MISSING_DECISION_REASON);
  }

  @Test
  @DisplayName("AVOID 는 KEEP 과 합쳐 2개, 전체는 pick-max 까지 — 넘치면 확신 낮은 ADD 부터 뺀다")
  void capsTrimAddsFirst() {
    properties.setPickMax(5);
    MorningAdviceResponse response = new MorningAdviceResponse(List.of(),
        List.of(addition("000005", "AVOID", "0.60", "a1"), addition("000006", "AVOID", "0.55", "a2")), "s");
    List<CandidateRow> more = new java.util.ArrayList<>(candidates);
    MorningAdviceGuard.Result r = guard.validate(response, evening, more);

    assertThat(r.stats()).containsEntry("truncatedAvoid", 1);
    assertThat(r.added()).extracting(PickRow::ticker).containsExactly("000005");
    assertThat(r.picks()).hasSize(5);

    properties.setPickMax(4);
    MorningAdviceGuard.Result capped = guard.validate(new MorningAdviceResponse(List.of(), List.of(addition("000005", "LONG", "0.90", "a")), "s"),
        evening, candidates);
    assertThat(capped.stats()).containsEntry("truncated", 1);
    assertThat(capped.picks()).extracting(PickRow::ticker).doesNotContain("000005").hasSize(4);
  }

  @Test
  @DisplayName("응답이 null 이면 전부 KEEP (모델 누락을 조용한 제외로 바꾸지 않는다)")
  void nullResponseKeepsAll() {
    MorningAdviceGuard.Result r = guard.validate(null, evening, candidates);
    assertThat(r.kept()).hasSize(4);
    assertThat(r.drops()).isEmpty();
    assertThat(r.stats()).containsEntry("missingDecision", 4);
  }

  private static PickRow pick(String ticker, int rank, PickDirection direction, double conviction) {
    return PickRow.builder().ticker(ticker).pickRank(rank).direction(direction).conviction(conviction).thesis("thesis-" + ticker).riskNote("risk")
        .cited(List.of()).citedNews(List.of()).build();
  }

  private static CandidateRow cand(String ticker) {
    return CandidateRow.builder().ticker(ticker).quantRank(1).quantScore(0.5).stockName("종목" + ticker).marketType("KOSPI").benchIndexCode("0001")
        .signals(Map.of()).features(Map.of()).build();
  }

  private static MorningAdviceResponse.Decision decision(String ticker, String action, String reason) {
    return new MorningAdviceResponse.Decision(ticker, action, reason);
  }

  private static MorningAdviceResponse.Addition addition(String ticker, String direction, String conviction, String reason) {
    return new MorningAdviceResponse.Addition(ticker, direction, conviction, "thesis", "risk", reason);
  }
}
