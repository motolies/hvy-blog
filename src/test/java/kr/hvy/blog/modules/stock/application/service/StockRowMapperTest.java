package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.dto.KisHolidayResponse;
import kr.hvy.blog.modules.stock.client.dto.KisIndexChartResponse;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.EtfNavRow;
import kr.hvy.blog.modules.stock.domain.model.HolidayRow;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockRowMapperTest {

  @Test
  @DisplayName("일봉 캔들은 등락률을 전일대비/전일종가로 계산하고 필수값 누락 행은 버린다")
  void dailyPriceRows() {
    List<KisDailyChartResponse.Candle> candles = List.of(
        new KisDailyChartResponse.Candle("20260903", "71000", "70000", "71500", "69500", "12345678", "870000000000",
            "00", "0.00", "N", "2", "1000", ""),
        new KisDailyChartResponse.Candle("20260902", "", "", "", "", "0", "0", "", "", "", "", "", ""));

    List<DailyPriceRow> rows = StockRowMapper.toDailyPriceRows("005930", candles);

    assertThat(rows).hasSize(1);
    DailyPriceRow row = rows.get(0);
    assertThat(row.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    assertThat(row.close()).isEqualByComparingTo("71000");
    assertThat(row.changeRate()).isEqualByComparingTo(new BigDecimal("1.4286")); // 1000 / 70000
    assertThat(row.flngClsCode()).isEqualTo("00");
    assertThat(row.revalReason()).isNull();
    assertThat(StockRowMapper.hasCorporateActionHint(row)).isFalse();
  }

  @Test
  @DisplayName("락 구분·수정주가 플래그·분할비율이 있으면 기업행사 힌트다")
  void corporateActionHint() {
    DailyPriceRow split = StockRowMapper.toDailyPriceRows("005930", List.of(
        new KisDailyChartResponse.Candle("20180504", "53000", "53000", "53900", "51800", "0", "0", "01", "50.00", "Y",
            "2", "0", "01"))).get(0);
    assertThat(StockRowMapper.hasCorporateActionHint(split)).isTrue();
    assertThat(split.splitRate()).isEqualByComparingTo("50.00");
    assertThat(StockRowMapper.changeRate(new BigDecimal("100"), new BigDecimal("100"))).isNull(); // 전일종가 0
  }

  @Test
  @DisplayName("지수 캔들·휴장일 응답을 행으로 바꾼다")
  void indexAndHolidayRows() {
    List<IndexDailyRow> index = StockRowMapper.toIndexRows("0001", List.of(
        new KisIndexChartResponse.Candle("20260903", "2650.12", "2640.00", "2660.55", "2635.10", "500000", "9000000", "N")));
    assertThat(index).hasSize(1);
    assertThat(index.get(0).close()).isEqualByComparingTo("2650.12");
    assertThat(index.get(0).volume()).isEqualTo(500_000L);

    List<HolidayRow> holidays = StockRowMapper.toHolidayRows(List.of(
        new KisHolidayResponse.Day("20260903", "05", "Y", "Y", "Y", "Y"),
        new KisHolidayResponse.Day("20260905", "07", "N", "N", "N", "N"),
        new KisHolidayResponse.Day("bad", "01", "Y", "Y", "Y", "Y")));
    assertThat(holidays).hasSize(2);
    assertThat(holidays.get(0).isOpen()).isTrue();
    assertThat(holidays.get(1).isOpen()).isFalse();
  }

  @Test
  @DisplayName("ETF NAV 행: 날짜·종가 없는 행은 버리고 같은 날짜는 앞 것을 남기며 NAV 소수는 보존한다")
  void etfNavRows() {
    List<KisEtfNavResponse.Row> rows = List.of(
        new KisEtfNavResponse.Row("20260904", "45000", "100", "2", "0.22", "1234567", "100", "-0.22", "-100.1234", "45100.1234", "2", "50.5", "0.11"),
        new KisEtfNavResponse.Row("20260904", "1", "", "", "", "", "", "", "", "", "", "", ""),
        new KisEtfNavResponse.Row("", "45000", "", "", "", "", "", "", "", "", "", "", ""),
        new KisEtfNavResponse.Row("20260903", "", "", "", "", "", "", "", "", "", "", "", ""));

    List<EtfNavRow> mapped = StockRowMapper.toEtfNavRows("069500", rows);

    assertThat(mapped).hasSize(1);
    EtfNavRow row = mapped.get(0);
    assertThat(row.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 4));
    assertThat(row.close()).isEqualByComparingTo("45000");
    assertThat(row.nav()).isEqualByComparingTo("45100.1234");
    assertThat(row.disparityRate()).isEqualByComparingTo("-0.22");
    assertThat(row.volume()).isEqualTo(1_234_567L);
    assertThat(row.navPrevDiffSign()).isEqualTo("2");
  }
}
