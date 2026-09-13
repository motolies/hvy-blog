package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 교훈 condition 술어의 기계 판정 규칙.
 */
class LessonConditionTest {

  private final CandidateRow candidate = CandidateRow.builder().ticker("005930").sectorCode("G2510")
      .signals(Map.of("TV_SURGE", new SignalValue(0.95, 0.1, 2.1), "MOM_20D", new SignalValue(0.4, 0.12, 0.01))).appliedLessonIds(List.of()).build();

  @Test
  @DisplayName("regime·sector·signal(op,pct) 조합이 전부 참일 때만 매치, null 키는 무시")
  void matches() {
    assertThat(LessonCondition.matches(Map.of("regime", "RISK_OFF", "signal", "TV_SURGE", "op", ">=", "pct", 0.9), candidate, MarketRegimeCode.RISK_OFF)).isTrue();
    assertThat(LessonCondition.matches(Map.of("regime", "RISK_OFF", "signal", "TV_SURGE", "op", ">=", "pct", 0.9), candidate, MarketRegimeCode.RISK_ON)).isFalse();
    assertThat(LessonCondition.matches(Map.of("regime", "RISK_OFF"), candidate, null)).as("국면 미상이면 국면 조건은 거짓").isFalse();
    assertThat(LessonCondition.matches(Map.of("sector", "G2510"), candidate, null)).isTrue();
    assertThat(LessonCondition.matches(Map.of("sector", "G3020"), candidate, null)).isFalse();
    assertThat(LessonCondition.matches(Map.of("signal", "MOM_20D", "op", "<", "pct", 0.5), candidate, null)).isTrue();
    assertThat(LessonCondition.matches(Map.of("signal", "MOM_20D", "op", ">", "pct", 0.5), candidate, null)).isFalse();
    assertThat(LessonCondition.matches(Map.of("signal", "VOL_20D", "op", ">", "pct", 0.5), candidate, null)).as("시그널 없음").isFalse();
    Map<String, Object> nullRegime = new HashMap<>();
    nullRegime.put("regime", null);
    nullRegime.put("sector", "G2510");
    assertThat(LessonCondition.matches(nullRegime, candidate, null)).as("null 키는 조건에서 제외").isTrue();
  }

  @Test
  @DisplayName("형식: 알려진 키만, 최소 하나 non-null, signal 이 있으면 op·pct 필수")
  void wellFormed() {
    assertThat(LessonCondition.isWellFormed(Map.of("regime", "RISK_ON"))).isTrue();
    assertThat(LessonCondition.isWellFormed(Map.of("signal", "TV_SURGE", "op", ">=", "pct", 0.9))).isTrue();
    assertThat(LessonCondition.isWellFormed(Map.of("signal", "TV_SURGE"))).isFalse();
    assertThat(LessonCondition.isWellFormed(Map.of("op", ">="))).isFalse();
    assertThat(LessonCondition.isWellFormed(Map.of("ticker", "005930"))).isFalse();
    assertThat(LessonCondition.isWellFormed(Map.of())).isFalse();
  }
}
