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
  @DisplayName("응답들을 결산기로 합치고 분기는 +45일, 연간은 +90일 available_from 을 붙인다")
  void mergeAndAvailability() {
    List<Map<String, String>> income = List.of(
        Map.of("stac_yymm", "202406", "sale_account", "740,650.00", "bsop_prti", "104,439", "thtr_ntin", "98,413"),
        Map.of("stac_yymm", "202403", "sale_account", "719,156", "bsop_prti", "66,060", "thtr_ntin", "67,547"));
    List<Map<String, String>> balance = List.of(
        Map.of("stac_yymm", "2024.06", "total_aset", "4,850,000", "total_cptl", "3,800,000", "total_lblt", "1,050,000"));
    List<Map<String, String>> ratio = List.of(
        Map.of("stac_yymm", "202406", "roe_val", "9.87", "lblt_rate", "27.6", "grs", "17.5", "ntin_inrt", "470.9"),
        Map.of("stac_yymm", "", "roe_val", "1"));

    List<FinancialRow> rows = FinancialRowMapper.merge("005930", true, List.of(income, balance, ratio));

    assertThat(rows).hasSize(2);
    FinancialRow q1 = rows.get(0);
    assertThat(q1.fiscalPeriod()).isEqualTo("202403");
    assertThat(q1.periodType()).isEqualTo(FinancialRow.PERIOD_QUARTER);
    assertThat(q1.availableFrom()).isEqualTo(LocalDate.of(2024, 3, 31).plusDays(45));
    assertThat(q1.availableRule()).isEqualTo("LAG_45D");
    assertThat(q1.totalAsset()).isNull();
    assertThat(q1.hasExtendedMetrics()).isFalse();

    FinancialRow q2 = rows.get(1);
    assertThat(q2.revenue()).isEqualTo(740_650L);
    assertThat(q2.operatingProfit()).isEqualTo(104_439L);
    assertThat(q2.totalEquity()).isEqualTo(3_800_000L);
    assertThat(q2.roe()).isEqualByComparingTo("9.87");
    assertThat(q2.debtRatio()).isEqualByComparingTo("27.6");
    assertThat(q2.netIncomeGrowth()).isEqualByComparingTo("470.9");
    assertThat(q2.rawJson()).contains("\"total_aset\"").contains("\"grs\"");

    assertThat(FinancialRowMapper.availableFrom("202312", false)).isEqualTo(LocalDate.of(2024, 3, 30));
    assertThat(FinancialRowMapper.normalizePeriod("2024-06")).isEqualTo("202406");
    assertThat(FinancialRowMapper.normalizePeriod("24")).isNull();
  }

  @Test
  @DisplayName("6종 병합: 성장성·수익성·안정성 지표가 컬럼으로 들어가고, 동명 grs·lblt_rate 는 나중 소스(전문 API)가 이긴다")
  void mergeSixSourcesWithPrecedence() {
    List<Map<String, String>> income = List.of(Map.of("stac_yymm", "202312", "sale_account", "100"));
    List<Map<String, String>> balance = List.of(Map.of("stac_yymm", "202312", "total_aset", "500"));
    List<Map<String, String>> ratio = List.of(Map.of("stac_yymm", "202312", "grs", "17.5", "lblt_rate", "27.6", "roe_val", "9.87", "ntin_inrt", "3.3"));
    List<Map<String, String>> growth = List.of(Map.of("stac_yymm", "202312", "grs", "18.0", "bsop_prfi_inrt", "12.1", "equt_inrt", "5.5", "totl_aset_inrt", "4.4"));
    List<Map<String, String>> profit = List.of(Map.of("stac_yymm", "202312", "cptl_ntin_rate", "6.6", "self_cptl_ntin_inrt", "9.87", "sale_ntin_rate", "7.7", "sale_totl_rate", "30.3"));
    List<Map<String, String>> stability = List.of(Map.of("stac_yymm", "202312", "lblt_rate", "28.0", "bram_depn", "11.1", "crnt_rate", "150.5", "quck_rate", "120.2"));

    FinancialRow row = FinancialRowMapper.merge("005930", false, List.of(income, balance, ratio, growth, profit, stability)).get(0);

    assertThat(row.revenueGrowth()).isEqualByComparingTo("18.0");          // 성장성 grs 우선
    assertThat(row.debtRatio()).isEqualByComparingTo("28.0");              // 안정성 lblt_rate 우선
    assertThat(row.netIncomeGrowth()).isEqualByComparingTo("3.3");
    assertThat(row.operatingProfitGrowth()).isEqualByComparingTo("12.1");
    assertThat(row.equityGrowth()).isEqualByComparingTo("5.5");
    assertThat(row.assetGrowth()).isEqualByComparingTo("4.4");
    assertThat(row.roa()).isEqualByComparingTo("6.6");
    assertThat(row.netMargin()).isEqualByComparingTo("7.7");
    assertThat(row.grossMargin()).isEqualByComparingTo("30.3");
    assertThat(row.currentRatio()).isEqualByComparingTo("150.5");
    assertThat(row.quickRatio()).isEqualByComparingTo("120.2");
    assertThat(row.borrowingDependency()).isEqualByComparingTo("11.1");
    assertThat(row.hasExtendedMetrics()).isTrue();
    assertThat(row.rawJson()).contains("\"self_cptl_ntin_inrt\"");        // 중복 지표는 raw_json 에만
  }

  @Test
  @DisplayName("sameValues 는 수치 19개만 비교한다 (raw_json 차이는 정정이 아니고, 확장 지표 변화는 정정이다)")
  void sameValues() {
    FinancialRow a = single(Map.of("stac_yymm", "202312", "sale_account", "100"));
    FinancialRow b = single(Map.of("stac_yymm", "202312", "sale_account", "100", "x", "y"));
    FinancialRow c = single(Map.of("stac_yymm", "202312", "sale_account", "101"));
    FinancialRow d = single(Map.of("stac_yymm", "202312", "sale_account", "100", "cptl_ntin_rate", "6.6"));
    FinancialRow e = single(Map.of("stac_yymm", "202312", "sale_account", "100", "cptl_ntin_rate", "7.0"));
    assertThat(a.sameValues(b)).isTrue();
    assertThat(a.sameValues(c)).isFalse();
    assertThat(a.sameValues(null)).isFalse();
    assertThat(a.sameValues(d)).isFalse();
    assertThat(a.sameCoreValues(d)).isTrue();   // 핵심은 같고 확장만 채워짐 → 채움 규칙 대상
    assertThat(d.sameValues(e)).isFalse();      // 확장 지표 정정
    assertThat(d.sameExtendedValues(e)).isFalse();
  }

  private static FinancialRow single(Map<String, String> row) {
    return FinancialRowMapper.merge("005930", false, List.of(List.of(row))).get(0);
  }
}
