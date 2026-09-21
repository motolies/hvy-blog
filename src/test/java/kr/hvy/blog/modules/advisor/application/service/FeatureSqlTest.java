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
  @DisplayName("점수화 가능한 시그널은 표현식이 있는 13개, IC 학습 대상은 밸류를 뺀 12개다 (advice-v6: 섹터 모멘텀 2개 추가, 사전 합 1.10)")
  void catalog() {
    assertThat(SignalCode.scorable()).hasSize(13).doesNotContain(SignalCode.GLOBAL_LINK)
        .contains(SignalCode.SECTOR_MOM_20D, SignalCode.SECTOR_MOM_60D);
    assertThat(SignalCode.learnable()).hasSize(12).doesNotContain(SignalCode.VALUE_RANK, SignalCode.GLOBAL_LINK);
    double baseSum = SignalCode.scorable().stream().mapToDouble(SignalCode::getBaseWeight).sum();
    // 점수는 Σw 로 정규화하므로 사전 합이 1.00 일 필요는 없다 — 시드(advisor-seed.sql)와 같은 값인지만 고정한다
    assertThat(baseSum).isCloseTo(1.10, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(SignalCode.SECTOR_MOM_60D.getExpression()).isEqualTo("f.sector_rs_60d");
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
    assertThat(values).contains("('MOM_20D', (f.ret_20d))").contains("('VOL_20D', -(f.vol_20d))").contains("('SECTOR_MOM_20D', (f.sector_rs_20d))")
        .doesNotContain("VALUE_RANK");
  }

  @Test
  @DisplayName("특징 CTE 는 기준일 이하만 본다 — 미래를 보는 LEAD·'> :to' 가 없다 — 그리고 시장은 :markets 로 한정한다")
  void noLookahead() {
    String ctes = FeatureSql.featureCtes();
    assertThat(ctes).doesNotContain("LEAD(").doesNotContain("> :to");
    assertThat(ctes).contains("BETWEEN :from AND :to");
    assertThat(ctes).as("advice-v5: 스크리닝·IC 가 같은 시장 필터를 쓴다").contains("WHERE ms.market_type IN (:markets)");
    // advice-v6: 업종 지수는 같은 날짜 행만 조인한다(six.trade_date = m.trade_date) — 기준일 뒤 행을 볼 수 없다
    assertThat(ctes).contains("LEFT JOIN mv_stock_index_metric six ON six.index_code = sm.sector_code AND six.trade_date = m.trade_date")
        .contains("six.ret_5d - ix.ret_5d AS sector_rs_5d").contains("six.ret_20d - ix.ret_20d AS sector_rs_20d").contains("six.ret_60d - ix.ret_60d AS sector_rs_60d");
  }
}
