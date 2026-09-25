package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.hvy.blog.modules.advisor.client.llm.LongTermNarrativeResponse;
import kr.hvy.blog.modules.advisor.client.llm.LongTermNarrativeResponse.Narrative;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장기 서술 가드(M8): 규칙 결과가 정본이고 LLM 은 서술만 붙인다.
 */
class LongTermNarrativeGuardTest {

  static final List<String> RULE = List.of("A", "B", "C");

  @Test
  @DisplayName("LLM 이 순서를 바꾸고 규칙 밖 종목을 넣고 하나를 빼도 픽은 규칙 순서 A,B,C 그대로 — 서술은 티커로 매칭, 빠진 것은 '서술 없음'")
  void ruleOrderWinsOverLlm() {
    LongTermNarrativeResponse response = new LongTermNarrativeResponse(List.of(
        new Narrative("C", "c 근거", "c 리스크"),
        new Narrative("Z", "규칙 밖", "x"),
        new Narrative("A", "a 근거 <!channel>", "a 리스크"),
        new Narrative("A", "중복", "중복")), "총평");

    LongTermNarrativeGuard.Result r = LongTermNarrativeGuard.apply(RULE, response, null);

    assertThat(r.picks()).extracting(PickRow::ticker).containsExactly("A", "B", "C");
    assertThat(r.picks()).extracting(PickRow::pickRank).containsExactly(1, 2, 3);
    assertThat(r.picks()).allMatch(p -> p.direction() == PickDirection.LONG && p.conviction() == 0.55);
    assertThat(r.picks().get(0).thesis()).as("멘션 제거").isEqualTo("a 근거");
    assertThat(r.picks().get(1).thesis()).isEqualTo(LongTermNarrativeGuard.NO_NARRATIVE);
    assertThat(r.picks().get(1).riskNote()).isNull();
    assertThat(r.picks().get(2).thesis()).isEqualTo("c 근거");
    assertThat(r.summary()).isEqualTo("총평");
    assertThat(r.stats()).containsEntry("unknownTicker", 1).containsEntry("duplicate", 1).containsEntry("missing", 1)
        .containsEntry("ruleOverride", true).containsEntry("narrative", "PARTIAL");
  }

  @Test
  @DisplayName("규칙과 같은 집합·순서면 ruleOverride=false, narrative=OK")
  void matchingResponse() {
    LongTermNarrativeResponse response = new LongTermNarrativeResponse(List.of(new Narrative("A", "a", "ra"), new Narrative("B", "b", "rb"),
        new Narrative("C", "c", "rc")), "s");
    LongTermNarrativeGuard.Result r = LongTermNarrativeGuard.apply(RULE, response, null);
    assertThat(r.stats()).containsEntry("ruleOverride", false).containsEntry("narrative", "OK").containsEntry("missing", 0);
    assertThat(r.picks()).extracting(PickRow::riskNote).containsExactly("ra", "rb", "rc");
  }

  @Test
  @DisplayName("LLM 실패(fail-open): 응답 null 이면 규칙 픽 전부 '서술 없음', narrative=FAILED 와 실패 사유를 남긴다")
  void failOpen() {
    LongTermNarrativeGuard.Result r = LongTermNarrativeGuard.apply(RULE, null, "timeout");
    assertThat(r.picks()).extracting(PickRow::ticker).containsExactly("A", "B", "C");
    assertThat(r.picks()).extracting(PickRow::thesis).containsOnly(LongTermNarrativeGuard.NO_NARRATIVE);
    assertThat(r.summary()).isNull();
    assertThat(r.stats()).containsEntry("narrative", "FAILED").containsEntry("failure", "timeout").containsEntry("missing", 3)
        .containsEntry("ruleOverride", false);
  }
}
