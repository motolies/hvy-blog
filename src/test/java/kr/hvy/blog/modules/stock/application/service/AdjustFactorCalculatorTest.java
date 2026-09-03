package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.model.AdjustEventRow;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdjustFactorCalculatorTest {

  private static final LocalDate DATE = LocalDate.of(2018, 5, 4);

  private static CorporateActionRow action(CorporateActionType type, String before, String after, String cash) {
    return new CorporateActionRow("005930", DATE, type, dec(before), dec(after), dec(cash), CorporateActionSource.KSD, "{}");
  }

  private static BigDecimal dec(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  @Test
  @DisplayName("액면분할 5000→100: 가격 0.02배, 거래량 50배 (삼성전자 2018)")
  void split() {
    AdjustEventRow event = AdjustFactorCalculator.toEvent(action(CorporateActionType.SPLIT, "5000", "100", null), null).orElseThrow();
    assertThat(event.priceFactor()).isEqualByComparingTo("0.02");
    assertThat(event.volumeFactor()).isEqualByComparingTo("50");
    assertThat(event.effectiveDate()).isEqualTo(DATE);
  }

  @Test
  @DisplayName("무상증자 1주당 1주: 가격 1/2, 거래량 2배")
  void bonusIssue() {
    AdjustEventRow event = AdjustFactorCalculator.toEvent(action(CorporateActionType.BONUS_ISSUE, null, "1", null), null).orElseThrow();
    assertThat(event.priceFactor()).isEqualByComparingTo("0.5");
    assertThat(event.volumeFactor()).isEqualByComparingTo("2");
  }

  @Test
  @DisplayName("유상증자 배정율 0.2·발행가 8000·전일종가 10000: 권리락 기준가 방식 (10000+1600)/(1.2×10000)")
  void rightsIssue() {
    CorporateActionRow action = action(CorporateActionType.RIGHTS_ISSUE, null, "0.2", "8000");
    AdjustEventRow event = AdjustFactorCalculator.toEvent(action, new BigDecimal("10000")).orElseThrow();
    assertThat(event.priceFactor()).isEqualByComparingTo(new BigDecimal("0.9666666667"));
    assertThat(event.volumeFactor()).isEqualByComparingTo("1.2");
    // 전일종가가 없으면 계산 불가
    assertThat(AdjustFactorCalculator.toEvent(action, null)).isEmpty();
  }

  @Test
  @DisplayName("감자 배정율은 1 미만이면 잔존 비율, 1 초과면 병합 비율로 해석한다")
  void capitalReduction() {
    AdjustEventRow remain = AdjustFactorCalculator.toEvent(action(CorporateActionType.CAPITAL_REDUCTION, null, "0.1", null), null).orElseThrow();
    assertThat(remain.priceFactor()).isEqualByComparingTo("10");
    assertThat(remain.volumeFactor()).isEqualByComparingTo("0.1");

    AdjustEventRow merge = AdjustFactorCalculator.toEvent(action(CorporateActionType.CAPITAL_REDUCTION, null, "5", null), null).orElseThrow();
    assertThat(merge.priceFactor()).isEqualByComparingTo("5");
    assertThat(merge.volumeFactor()).isEqualByComparingTo("0.2");
  }

  @Test
  @DisplayName("해석 불가·의미 없는 행사는 이벤트를 만들지 않는다")
  void skipsMeaningless() {
    assertThat(AdjustFactorCalculator.toEvent(action(CorporateActionType.SPLIT, "5000", "5000", null), null)).isEmpty();
    assertThat(AdjustFactorCalculator.toEvent(action(CorporateActionType.SPLIT, null, "100", null), null)).isEmpty();
    assertThat(AdjustFactorCalculator.toEvent(action(CorporateActionType.CAPITAL_REDUCTION, null, "1", null), null)).isEmpty();
    assertThat(AdjustFactorCalculator.toEvent(action(CorporateActionType.DIVIDEND, null, null, "361"), null)).isEmpty();
    Optional<AdjustEventRow> absurd = AdjustFactorCalculator.toEvent(action(CorporateActionType.SPLIT, "1", "100000", null), null);
    assertThat(absurd).isEmpty();
  }
}
