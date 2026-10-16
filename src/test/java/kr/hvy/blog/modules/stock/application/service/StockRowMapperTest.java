package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.dto.KisHolidayResponse;
import kr.hvy.blog.modules.stock.client.KisJson;
import kr.hvy.blog.modules.stock.client.dto.KisIndexChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.model.MarketInvestorRow;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.EtfNavRow;
import kr.hvy.blog.modules.stock.domain.model.HolidayRow;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockRowMapperTest {

  @Test
  void investorAmounts_NormalizeExactlyAndPreferTotal() {
    var raw = investor("-1387781", "239692", "0", "", "-68410", "-60000", "-8410");
    var row = StockRowMapper.toInvestorRows("005930", List.of(raw, raw)).getFirst();
    assertThat(row.foreignNetAmt()).isEqualTo(-1_387_781_000_000L);
    assertThat(row.institutionNetAmt()).isEqualTo(239_692_000_000L);
    assertThat(row.individualNetAmt()).isZero();
    assertThat(row.pensionNetAmt()).isNull();
    assertThat(row.otherNetAmt()).isEqualTo(-68_410_000_000L);
    assertThat(row.foreignNetQty()).isEqualTo(-123L);
    assertThat(StockRowMapper.toInvestorRows("005930", List.of(raw, raw))).hasSize(1);
  }

  @Test
  void investorAmounts_MissingAndFallbackRemainDistinctFromZero() {
    var row = StockRowMapper.toInvestorRows("005930", List.of(
        investor(null, " ", "0", "1.25", null, "-2", "3"))).getFirst();
    assertThat(row.foreignNetAmt()).isNull();
    assertThat(row.institutionNetAmt()).isNull();
    assertThat(row.individualNetAmt()).isZero();
    assertThat(row.pensionNetAmt()).isEqualTo(1_250_000L);
    assertThat(row.otherNetAmt()).isEqualTo(1_000_000L);
    assertThat(StockRowMapper.toInvestorRows("005930", List.of(
        investor(null, null, null, null, "", "1", null))).getFirst().otherNetAmt()).isNull();
    assertThat(StockRowMapper.toInvestorRows("005930", List.of(
        investor(null, null, null, null, "0", "1", "2"))).getFirst().otherNetAmt()).isZero();
  }

  @Test
  void investorAmounts_OverflowOrFractionalWonFailsCollection() {
    for (String value : List.of("9223372036854.775808", "-9223372036854.775809", "0.0000001")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> StockRowMapper.toInvestorRows("005930",
          List.of(investor(value, null, null, null, null, null, null))))
          .isInstanceOf(ArithmeticException.class);
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> StockRowMapper.toInvestorRows("005930",
        List.of(investor(null, null, null, null, null, "9223372036854", "1"))))
        .isInstanceOf(ArithmeticException.class);
  }

  // 계획에 제시된 10/13 금액 기반 표본. 원본 5거래일 응답은 별도 확보해야 한다.
  private static kr.hvy.blog.modules.stock.client.dto.KisInvestorDailyResponse.Row investor(
      String foreign, String institution, String individual, String pension, String total, String corp, String org) {
    return new kr.hvy.blog.modules.stock.client.dto.KisInvestorDailyResponse.Row(
        "20261013", "0", "-123", "0", "45", "0", foreign, individual, institution, pension, total, corp, org);
  }

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

  @Test
  @DisplayName("밸류에이션: PER/PBR/EPS/BPS 의 KIS \"0.00\"(미산출)은 null, 음수 EPS(적자)·양수는 그대로, 시총 억원은 원 (2026-10-16)")
  void valuationZeroRatiosAreNull() {
    var zero = StockRowMapper.toValuationRow("005930", LocalDate.of(2026, 10, 15), price("0.00", "0.00", "0.00", "0.00"));
    assertThat(zero.per()).isNull();
    assertThat(zero.pbr()).isNull();
    assertThat(zero.eps()).isNull();
    assertThat(zero.bps()).isNull();
    assertThat(zero.marketCap()).isEqualTo(4_000_000L * 100_000_000L);
    var real = StockRowMapper.toValuationRow("005930", LocalDate.of(2026, 10, 15), price("12.3", "1.05", "-123", "45000"));
    assertThat(real.per()).isEqualByComparingTo("12.3");
    assertThat(real.pbr()).isEqualByComparingTo("1.05");
    assertThat(real.eps()).as("적자 EPS 는 유지").isEqualByComparingTo("-123");
    assertThat(real.bps()).isEqualByComparingTo("45000");
    assertThat(StockRowMapper.toValuationRow("005930", LocalDate.of(2026, 10, 15), null)).isNull();
  }

  private static kr.hvy.blog.modules.stock.client.dto.KisPriceResponse.Output price(String per, String pbr, String eps, String bps) {
    return new kr.hvy.blog.modules.stock.client.dto.KisPriceResponse.Output("005930", "70000", null, null, null, null, null, "4000000", "5969782550",
        per, pbr, eps, bps, null, null, "50.5", null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("시장별 투자자: 31개 키 JSON 이 전부 역직렬화되고(접미사 예외 5개 포함) 15주체 대금은 원으로 환산되고 수량은 천주 그대로 행에 들어간다")
  void marketInvestorRows() throws Exception {
    String json;
    try (var in = getClass().getClassLoader().getResourceAsStream("kis/market-investor-daily.json")) {
      assertThat(in).isNotNull();
      json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    KisMarketInvestorResponse response = KisJson.read(json, KisMarketInvestorResponse.class);
    KisMarketInvestorResponse.Row raw = response.output().get(0);
    // 픽스처는 키 순서대로 0..30 을 넣었다: 날짜, (수량, 대금) × 15
    assertThat(raw.foreignNetQty()).isEqualTo("1");
    assertThat(raw.foreignRegNetAmt()).isEqualTo("4");      // frgn_reg_ntby_pbmn (접미사 예외)
    assertThat(raw.privateFundNetQty()).isEqualTo("15");    // pe_fund_ntby_vol (접미사 예외)
    assertThat(raw.otherCorpNetQty()).isEqualTo("29");      // etc_corp_ntby_vol (접미사 예외)
    assertThat(raw.otherCorpNetAmt()).isEqualTo("30");

    List<MarketInvestorRow> rows = StockRowMapper.toMarketInvestorRows(MarketType.KOSDAQ, List.of(raw, raw));
    assertThat(rows).hasSize(1);
    MarketInvestorRow row = rows.get(0);
    assertThat(row.marketType()).isEqualTo(MarketType.KOSDAQ);
    assertThat(row.tradeDate()).isEqualTo(LocalDate.of(2026, 9, 4));
    assertThat(row.foreignNetQty()).isEqualTo(1L);                 // 수량은 천주 그대로
    assertThat(row.foreignNetAmt()).isEqualTo(2_000_000L);         // 대금은 백만원 → 원
    assertThat(row.pensionNetQty()).isEqualTo(23L);
    assertThat(row.pensionNetAmt()).isEqualTo(24_000_000L);
    assertThat(row.otherCorpNetAmt()).isEqualTo(30_000_000L);
  }

  @Test
  @DisplayName("시장별 투자자: 대금(백만원)은 정확히 1,000,000배 원으로, 수량(천주)은 원천 그대로 — KOSPI 2026-10-13 실측값")
  void marketInvestorAmounts_NormalizeExactlyAndKeepQty() {
    var raw = marketRow("-4383", "-4578915");
    List<MarketInvestorRow> rows = StockRowMapper.toMarketInvestorRows(MarketType.KOSPI, List.of(raw, raw));
    assertThat(rows).hasSize(1);
    MarketInvestorRow row = rows.getFirst();
    assertThat(row.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(row.foreignNetAmt()).isEqualTo(-4_578_915_000_000L);
    assertThat(row.foreignNetQty()).isEqualTo(-4_383L);
    assertThat(StockRowMapper.toMarketInvestorRows(MarketType.KOSPI, List.of(marketRow("7", "1.5"))).getFirst().foreignNetAmt())
        .isEqualTo(1_500_000L);
    assertThat(StockRowMapper.toMarketInvestorRows(MarketType.KOSPI, List.of(marketRow("0", "0"))).getFirst().foreignNetAmt()).isZero();
  }

  @Test
  @DisplayName("시장별 투자자: 결측(빈 문자열·공백·'-'·null)은 0 이 아니라 null 로 남는다")
  void marketInvestorAmounts_MissingStaysNull() {
    MarketInvestorRow blank = StockRowMapper.toMarketInvestorRows(MarketType.KOSDAQ, List.of(marketRow("", " "))).getFirst();
    assertThat(blank.foreignNetQty()).isNull();
    assertThat(blank.foreignNetAmt()).isNull();
    assertThat(blank.pensionNetAmt()).isNull();
    assertThat(blank.otherCorpNetQty()).isNull();
    MarketInvestorRow dash = StockRowMapper.toMarketInvestorRows(MarketType.KOSDAQ, List.of(marketRow("-", null))).getFirst();
    assertThat(dash.foreignNetQty()).isNull();
    assertThat(dash.foreignNetAmt()).isNull();
  }

  @Test
  @DisplayName("시장별 투자자: 원 미만 소수·long 범위 초과 대금은 절삭하지 않고 수집 실패로 전파한다")
  void marketInvestorAmounts_OverflowOrFractionalWonFailsCollection() {
    for (String value : List.of("9223372036854.775808", "-9223372036854.775809", "0.0000001")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> StockRowMapper.toMarketInvestorRows(MarketType.KOSPI,
          List.of(marketRow("1", value))))
          .isInstanceOf(ArithmeticException.class);
    }
  }

  /** 날짜 2026-10-13, 외국인 수량·대금만 지정하고 나머지 28개 필드는 빈 문자열(결측). */
  private static KisMarketInvestorResponse.Row marketRow(String foreignQty, String foreignAmt) {
    return new KisMarketInvestorResponse.Row("20261013", foreignQty, foreignAmt,
        "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
  }
}
