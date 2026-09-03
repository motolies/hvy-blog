package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.KsdInfoKind;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CorporateActionMapperTest {

  @Test
  @DisplayName("액면교체: 변경상장일을 효력일로, 액면가 감소는 SPLIT·증가는 REVERSE_SPLIT")
  void revSplit() {
    List<CorporateActionRow> rows = CorporateActionMapper.fromKsd(KsdInfoKind.REV_SPLIT, List.of(
        Map.of("record_date", "2018/04/30", "sht_cd", "A005930", "inter_bf_face_amt", "5,000", "inter_af_face_amt", "100",
            "td_stop_dt", "2018/04/30~2018/05/03", "list_dt", "20180504"),
        Map.of("record_date", "20230601", "sht_cd", "000020", "inter_bf_face_amt", "100", "inter_af_face_amt", "500", "list_dt", ""),
        Map.of("record_date", "20230601", "sht_cd", "", "inter_bf_face_amt", "100", "inter_af_face_amt", "500")));

    assertThat(rows).hasSize(2);
    CorporateActionRow split = rows.get(0);
    assertThat(split.ticker()).isEqualTo("005930");
    assertThat(split.effectiveDate()).isEqualTo(LocalDate.of(2018, 5, 4));
    assertThat(split.actionType()).isEqualTo(CorporateActionType.SPLIT);
    assertThat(split.ratioBefore()).isEqualByComparingTo("5000");
    assertThat(split.ratioAfter()).isEqualByComparingTo("100");
    assertThat(split.source()).isEqualTo(CorporateActionSource.KSD);
    assertThat(split.rawJson()).contains("\"list_dt\"");

    CorporateActionRow merge = rows.get(1);
    assertThat(merge.actionType()).isEqualTo(CorporateActionType.REVERSE_SPLIT);
    assertThat(merge.effectiveDate()).isEqualTo(LocalDate.of(2023, 6, 1)); // list_dt 없으면 기준일
  }

  @Test
  @DisplayName("증자·감자·배당은 유형별 효력일과 비율·금액을 채운다")
  void otherKinds() {
    CorporateActionRow bonus = CorporateActionMapper.fromKsd(KsdInfoKind.BONUS_ISSUE, List.of(
        Map.of("record_date", "20240102", "sht_cd", "035420", "fix_rate", "0.5", "right_dt", "20231229"))).get(0);
    assertThat(bonus.actionType()).isEqualTo(CorporateActionType.BONUS_ISSUE);
    assertThat(bonus.effectiveDate()).isEqualTo(LocalDate.of(2023, 12, 29));
    assertThat(bonus.ratioAfter()).isEqualByComparingTo("0.5");

    CorporateActionRow rights = CorporateActionMapper.fromKsd(KsdInfoKind.PAIDIN_CAPIN, List.of(
        Map.of("record_date", "20240102", "sht_cd", "035420", "fix_rate", "0.2", "fix_price", "8,000", "right_dt", "20231229"))).get(0);
    assertThat(rights.actionType()).isEqualTo(CorporateActionType.RIGHTS_ISSUE);
    assertThat(rights.cashAmount()).isEqualByComparingTo("8000");

    CorporateActionRow reduction = CorporateActionMapper.fromKsd(KsdInfoKind.CAP_DCRS, List.of(
        Map.of("record_date", "20240102", "sht_cd", "035420", "reduce_cap_rate", "0.1", "list_dt", "20240115"))).get(0);
    assertThat(reduction.actionType()).isEqualTo(CorporateActionType.CAPITAL_REDUCTION);
    assertThat(reduction.effectiveDate()).isEqualTo(LocalDate.of(2024, 1, 15));

    CorporateActionRow dividend = CorporateActionMapper.fromKsd(KsdInfoKind.DIVIDEND, List.of(
        Map.of("record_date", "20231231", "sht_cd", "005930", "per_sto_divi_amt", "361", "stk_divi_rate", ""))).get(0);
    assertThat(dividend.actionType()).isEqualTo(CorporateActionType.DIVIDEND);
    assertThat(dividend.cashAmount()).isEqualByComparingTo("361");
    assertThat(dividend.ratioAfter()).isNull();
  }

  @Test
  @DisplayName("일봉 힌트는 CHART_HINT 후보로 바뀌고, 종목코드·날짜 정규화가 동작한다")
  void chartHintsAndNormalization() {
    DailyPriceRow hint = new DailyPriceRow("005930", LocalDate.of(2018, 5, 4), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
        BigDecimal.ONE, 1, 1, null, null, null, "01", new BigDecimal("50"), "Y", null);
    List<CorporateActionRow> hints = CorporateActionMapper.fromChartHints(List.of(hint));
    assertThat(hints).hasSize(1);
    assertThat(hints.get(0).source()).isEqualTo(CorporateActionSource.CHART_HINT);
    assertThat(hints.get(0).actionType()).isEqualTo(CorporateActionType.CHART_HINT);
    assertThat(hints.get(0).rawJson()).contains("\"mod_yn\":\"Y\"");

    assertThat(CorporateActionMapper.normalizeTicker("A005930")).isEqualTo("005930");
    assertThat(CorporateActionMapper.normalizeTicker(" 005930 ")).isEqualTo("005930");
    assertThat(CorporateActionMapper.normalizeTicker("Q500001")).isNull(); // 7자리 ETN 은 A 접두가 아니면 제외
    assertThat(CorporateActionMapper.normalizeTicker("")).isNull();
    assertThat(CorporateActionMapper.date("2024.01.15")).isEqualTo(LocalDate.of(2024, 1, 15));
    assertThat(CorporateActionMapper.date("2024/01/15~2024/01/20")).isEqualTo(LocalDate.of(2024, 1, 15));
    assertThat(CorporateActionMapper.date("-")).isNull();
  }
}
