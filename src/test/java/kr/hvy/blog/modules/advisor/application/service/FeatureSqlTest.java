package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 조립 규약: 시그널 수만큼 절이 생기고, 가중치는 명명 파라미터로만 들어가며, 낮을수록 좋은 시그널은 부호가 뒤집힌다.
 */
class FeatureSqlTest {

  @Test
  @DisplayName("점수화 가능한 시그널은 표현식이 있는 11개, IC 학습 대상은 밸류를 뺀 10개다")
  void catalog() {
    assertThat(SignalCode.scorable()).hasSize(11).doesNotContain(SignalCode.GLOBAL_LINK);
    assertThat(SignalCode.learnable()).hasSize(10).doesNotContain(SignalCode.VALUE_RANK, SignalCode.GLOBAL_LINK);
    double baseSum = SignalCode.scorable().stream().mapToDouble(SignalCode::getBaseWeight).sum();
    assertThat(baseSum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  @DisplayName("백분위·원값 절은 시그널마다 하나씩, NULL 여부로 창을 나눈다")
  void percentRankColumns() {
    List<SignalCode> signals = SignalCode.scorable();
    String pct = FeatureSql.percentRankColumns(signals);
    String raw = FeatureSql.rawValueColumns(signals);
    for (SignalCode s : signals) {
      assertThat(pct).contains("AS p_" + s.getCode());
      assertThat(raw).contains("AS v_" + s.getCode());
    }
    assertThat(pct).contains("PARTITION BY f.trade_date, ((f.ret_20d) IS NULL)");
    assertThat(pct.split("PERCENT_RANK\\(\\)")).hasSize(signals.size() + 1);
  }

  @Test
  @DisplayName("점수 식은 :w_CODE 로만 가중치를 받고 낮을수록 좋은 시그널은 부호를 뒤집는다")
  void scoreExpressionAndParams() {
    List<SignalCode> signals = List.of(SignalCode.MOM_20D, SignalCode.VOL_20D);
    String expr = FeatureSql.scoreExpression(signals);
    assertThat(expr).contains(":w_MOM_20D * ((2 * COALESCE(p_MOM_20D, 0.5) - 1))")
        .contains(":w_VOL_20D * (-(2 * COALESCE(p_VOL_20D, 0.5) - 1))")
        .endsWith("/ :w_sum");
    Map<String, Object> params = FeatureSql.weightParams(Map.of("MOM_20D", 0.12, "VOL_20D", 0.03, "OTHER", 9.0), signals);
    assertThat(params).containsEntry("w_MOM_20D", 0.12).containsEntry("w_VOL_20D", 0.03).doesNotContainKey("w_OTHER");
    assertThat((Double) params.get("w_sum")).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(FeatureSql.weightParams(Map.of(), signals)).containsEntry("w_sum", 1.0);
  }

  @Test
  @DisplayName("IC 언피벗은 학습 시그널마다 (code, 값) 쌍이고 낮을수록 좋은 시그널은 음수화한다")
  void icValuesList() {
    String values = FeatureSql.icValuesList(SignalCode.learnable());
    assertThat(values).contains("('MOM_20D', (f.ret_20d))").contains("('VOL_20D', -(f.vol_20d))").doesNotContain("VALUE_RANK");
  }

  @Test
  @DisplayName("특징 CTE 는 기준일 이하만 본다 — 미래를 보는 LEAD·'> :to' 가 없다")
  void noLookahead() {
    String ctes = FeatureSql.featureCtes();
    assertThat(ctes).doesNotContain("LEAD(").doesNotContain("> :to");
    assertThat(ctes).contains("BETWEEN :from AND :to");
  }
}
