package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinancialRowMapperTest {

  @Test
  @DisplayName("세 응답을 결산기로 합치고 분기는 +45일, 연간은 +90일 available_from 을 붙인다")
  void mergeAndAvailability() {
    List<Map<String, String>> income = List.of(
        Map.of("stac_yymm", "202406", "sale_account", "740,650.00", "bsop_prti", "104,439", "thtr_ntin", "98,413"),
        Map.of("stac_yymm", "202403", "sale_account", "719,156", "bsop_prti", "66,060", "thtr_ntin", "67,547"));
    List<Map<String, String>> balance = List.of(
        Map.of("stac_yymm", "2024.06", "total_aset", "4,850,000", "total_cptl", "3,800,000", "total_lblt", "1,050,000"));
    List<Map<String, String>> ratio = List.of(
        Map.of("stac_yymm", "202406", "roe_val", "9.87", "lblt_rate", "27.6", "grs", "17.5", "ntin_inrt", "470.9"),
        Map.of("stac_yymm", "", "roe_val", "1"));

    List<FinancialRow> rows = FinancialRowMapper.merge("005930", true, income, balance, ratio);

    assertThat(rows).hasSize(2);
    FinancialRow q1 = rows.get(0);
    assertThat(q1.fiscalPeriod()).isEqualTo("202403");
    assertThat(q1.periodType()).isEqualTo(FinancialRow.PERIOD_QUARTER);
    assertThat(q1.availableFrom()).isEqualTo(LocalDate.of(2024, 3, 31).plusDays(45));
    assertThat(q1.availableRule()).isEqualTo("LAG_45D");
    assertThat(q1.totalAsset()).isNull();

    FinancialRow q2 = rows.get(1);
    assertThat(q2.revenue()).isEqualTo(740_650L);
    assertThat(q2.operatingProfit()).isEqualTo(104_439L);
    assertThat(q2.totalEquity()).isEqualTo(3_800_000L);
    assertThat(q2.roe()).isEqualByComparingTo("9.87");
    assertThat(q2.debtRatio()).isEqualByComparingTo("27.6");
    assertThat(q2.rawJson()).contains("\"total_aset\"").contains("\"grs\"");

    assertThat(FinancialRowMapper.availableFrom("202312", false)).isEqualTo(LocalDate.of(2024, 3, 30));
    assertThat(FinancialRowMapper.normalizePeriod("2024-06")).isEqualTo("202406");
    assertThat(FinancialRowMapper.normalizePeriod("24")).isNull();
  }

  @Test
  @DisplayName("sameValues 는 수치 컬럼만 비교한다 (raw_json 차이는 정정이 아니다)")
  void sameValues() {
    FinancialRow a = FinancialRowMapper.merge("005930", false, List.of(Map.of("stac_yymm", "202312", "sale_account", "100")),
        List.of(), List.of()).get(0);
    FinancialRow b = FinancialRowMapper.merge("005930", false, List.of(Map.of("stac_yymm", "202312", "sale_account", "100", "x", "y")),
        List.of(), List.of()).get(0);
    FinancialRow c = FinancialRowMapper.merge("005930", false, List.of(Map.of("stac_yymm", "202312", "sale_account", "101")),
        List.of(), List.of()).get(0);
    assertThat(a.sameValues(b)).isTrue();
    assertThat(a.sameValues(c)).isFalse();
    assertThat(a.sameValues(null)).isFalse();
  }
}
