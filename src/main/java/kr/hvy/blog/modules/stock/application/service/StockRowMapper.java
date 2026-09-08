package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.dto.KisHolidayResponse;
import kr.hvy.blog.modules.stock.client.dto.KisIndexChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisInvestorDailyResponse;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.EtfNavRow;
import kr.hvy.blog.modules.stock.domain.model.HolidayRow;
import kr.hvy.blog.modules.stock.domain.model.IndexDailyRow;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.model.InvestorDailyRow;
import kr.hvy.blog.modules.stock.domain.model.MarketInvestorRow;
import kr.hvy.blog.modules.stock.domain.model.ValuationRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * KIS 응답 DTO → 적재 행 변환. 값이 비어 NOT NULL 컬럼을 채울 수 없는 행은 버리고 로그만 남긴다.
 */
@Slf4j
public final class StockRowMapper {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal HUNDRED_MILLION = BigDecimal.valueOf(100_000_000L);

  private StockRowMapper() {
  }

  /**
   * 종목 일봉 캔들 → 행. 등락률은 output2 에 없어 전일대비/전일종가로 계산한다.
   */
  public static List<DailyPriceRow> toDailyPriceRows(String ticker, List<KisDailyChartResponse.Candle> candles) {
    List<DailyPriceRow> rows = new ArrayList<>(candles.size());
    for (KisDailyChartResponse.Candle c : candles) {
      LocalDate date = KisValues.date(c.tradeDate());
      BigDecimal open = KisValues.decimal(c.open());
      BigDecimal high = KisValues.decimal(c.high());
      BigDecimal low = KisValues.decimal(c.low());
      BigDecimal close = KisValues.decimal(c.close());
      if (date == null || open == null || high == null || low == null || close == null) {
        log.debug("일봉 필수값 누락(건너뜀): ticker={}, date={}", ticker, c.tradeDate());
        continue;
      }
      BigDecimal prevDiff = KisValues.decimal(c.prevDiff());
      rows.add(new DailyPriceRow(ticker, date, open, high, low, close,
          KisValues.longValue(c.volume(), 0L), KisValues.longValue(c.tradingValue(), 0L),
          prevDiff, blankToNull(c.prevDiffSign()), changeRate(close, prevDiff),
          blankToNull(c.flngClsCode()), KisValues.decimal(c.splitRate()), blankToNull(c.modYn()),
          blankToNull(c.revalReason())));
    }
    return rows;
  }

  /**
   * 지수 캔들 → 행.
   */
  public static List<IndexDailyRow> toIndexRows(String indexCode, List<KisIndexChartResponse.Candle> candles) {
    List<IndexDailyRow> rows = new ArrayList<>(candles.size());
    for (KisIndexChartResponse.Candle c : candles) {
      LocalDate date = KisValues.date(c.tradeDate());
      BigDecimal open = KisValues.decimal(c.open());
      BigDecimal high = KisValues.decimal(c.high());
      BigDecimal low = KisValues.decimal(c.low());
      BigDecimal close = KisValues.decimal(c.close());
      if (date == null || open == null || high == null || low == null || close == null) {
        log.debug("지수 일봉 필수값 누락(건너뜀): index={}, date={}", indexCode, c.tradeDate());
        continue;
      }
      rows.add(new IndexDailyRow(indexCode, date, open, high, low, close,
          KisValues.longValue(c.volume()), KisValues.longValue(c.tradingValue()), null));
    }
    return rows;
  }

  /**
   * 휴장일 응답 → 행.
   */
  public static List<HolidayRow> toHolidayRows(List<KisHolidayResponse.Day> days) {
    List<HolidayRow> rows = new ArrayList<>(days.size());
    for (KisHolidayResponse.Day d : days) {
      LocalDate date = KisValues.date(d.date());
      if (date == null) {
        continue;
      }
      rows.add(new HolidayRow(date, KisValues.flag(d.openDay()), KisValues.flag(d.businessDay()),
          KisValues.flag(d.tradingDay()), KisValues.flag(d.settlementDay())));
    }
    return rows;
  }

  /**
   * 투자자매매동향(일별) → 행. 기타 = 기타법인 + 기타단체 + 기타. 같은 날짜가 여러 페이지에 겹치면 뒤 것을 버린다.
   */
  public static List<InvestorDailyRow> toInvestorRows(String ticker, List<KisInvestorDailyResponse.Row> rows) {
    List<InvestorDailyRow> result = new ArrayList<>(rows.size());
    java.util.Set<LocalDate> seen = new java.util.HashSet<>();
    for (KisInvestorDailyResponse.Row r : rows) {
      LocalDate date = KisValues.date(r.tradeDate());
      if (date == null || !seen.add(date)) {
        continue;
      }
      long other = KisValues.longValue(r.etcNetAmt(), 0L) + KisValues.longValue(r.etcCorpNetAmt(), 0L)
          + KisValues.longValue(r.etcOrgNetAmt(), 0L);
      result.add(new InvestorDailyRow(ticker, date,
          KisValues.longValue(r.foreignNetAmt(), 0L), KisValues.longValue(r.institutionNetAmt(), 0L),
          KisValues.longValue(r.individualNetAmt(), 0L), KisValues.longValue(r.pensionNetAmt(), 0L), other,
          KisValues.longValue(r.foreignNetQty()), KisValues.longValue(r.institutionNetQty()),
          KisValues.longValue(r.individualNetQty())));
    }
    return result;
  }

  /**
   * 현재가 스냅샷 → 밸류에이션 행. 시가총액(hts_avls)은 억원이라 원으로 환산한다. 응답이 비면 null.
   */
  public static ValuationRow toValuationRow(String ticker, LocalDate tradeDate, KisPriceResponse.Output o) {
    if (o == null) {
      return null;
    }
    Long marketCap = null;
    BigDecimal capHundredMillion = KisValues.decimal(o.marketCapHundredMillion());
    if (capHundredMillion != null) {
      marketCap = capHundredMillion.multiply(HUNDRED_MILLION).setScale(0, RoundingMode.HALF_UP).longValue();
    }
    return new ValuationRow(ticker, tradeDate, marketCap, KisValues.longValue(o.listedShares()),
        KisValues.decimal(o.per()), KisValues.decimal(o.pbr()), KisValues.decimal(o.eps()), KisValues.decimal(o.bps()),
        KisValues.decimal(o.week52High()), KisValues.decimal(o.week52Low()), KisValues.decimal(o.foreignHoldRate()));
  }

  /**
   * ETF NAV 비교추이 → 행. 날짜·종가가 없는 행은 버리고, 같은 날짜가 겹치면 앞 것을 남긴다.
   */
  public static List<EtfNavRow> toEtfNavRows(String ticker, List<KisEtfNavResponse.Row> rows) {
    Map<LocalDate, EtfNavRow> byDate = new LinkedHashMap<>();
    for (KisEtfNavResponse.Row r : rows) {
      LocalDate date = KisValues.date(r.tradeDate());
      BigDecimal close = KisValues.decimal(r.close());
      if (date == null || close == null) {
        continue;
      }
      byDate.putIfAbsent(date, new EtfNavRow(ticker, date, close, KisValues.decimal(r.prevDiff()), blankToNull(r.prevDiffSign()),
          KisValues.decimal(r.changeRate()), KisValues.longValue(r.volume()), KisValues.decimal(r.nav()),
          KisValues.decimal(r.navPrevDiff()), blankToNull(r.navPrevDiffSign()), KisValues.decimal(r.navChangeRate()),
          KisValues.decimal(r.navDiff()), KisValues.decimal(r.disparityRate())));
    }
    return new ArrayList<>(byDate.values());
  }

  /**
   * 시장별 투자자매매동향 → 행. 날짜 없는 행은 버리고 같은 날짜는 앞 것을 남긴다.
   */
  public static List<MarketInvestorRow> toMarketInvestorRows(MarketType market, List<KisMarketInvestorResponse.Row> rows) {
    Map<LocalDate, MarketInvestorRow> byDate = new LinkedHashMap<>();
    for (KisMarketInvestorResponse.Row r : rows) {
      LocalDate date = KisValues.date(r.tradeDate());
      if (date == null) {
        continue;
      }
      byDate.putIfAbsent(date, new MarketInvestorRow(market, date,
          KisValues.longValue(r.foreignNetAmt()), KisValues.longValue(r.foreignNetQty()), KisValues.longValue(r.foreignRegNetAmt()), KisValues.longValue(r.foreignRegNetQty()), KisValues.longValue(r.foreignNregNetAmt()), KisValues.longValue(r.foreignNregNetQty()), KisValues.longValue(r.individualNetAmt()), KisValues.longValue(r.individualNetQty()), KisValues.longValue(r.institutionNetAmt()), KisValues.longValue(r.institutionNetQty()), KisValues.longValue(r.securitiesNetAmt()), KisValues.longValue(r.securitiesNetQty()), KisValues.longValue(r.investTrustNetAmt()), KisValues.longValue(r.investTrustNetQty()), KisValues.longValue(r.privateFundNetAmt()), KisValues.longValue(r.privateFundNetQty()), KisValues.longValue(r.bankNetAmt()), KisValues.longValue(r.bankNetQty()), KisValues.longValue(r.insuranceNetAmt()), KisValues.longValue(r.insuranceNetQty()), KisValues.longValue(r.merchantBankNetAmt()), KisValues.longValue(r.merchantBankNetQty()), KisValues.longValue(r.pensionNetAmt()), KisValues.longValue(r.pensionNetQty()), KisValues.longValue(r.otherNetAmt()), KisValues.longValue(r.otherNetQty()), KisValues.longValue(r.otherOrgNetAmt()), KisValues.longValue(r.otherOrgNetQty()), KisValues.longValue(r.otherCorpNetAmt()), KisValues.longValue(r.otherCorpNetQty())));
    }
    return new ArrayList<>(byDate.values());
  }

  /**
   * 등락률(%) = 전일대비 / 전일종가 × 100. 전일종가 = 종가 - 전일대비.
   */
  static BigDecimal changeRate(BigDecimal close, BigDecimal prevDiff) {
    if (close == null || prevDiff == null) {
      return null;
    }
    BigDecimal prevClose = close.subtract(prevDiff);
    if (prevClose.signum() == 0) {
      return null;
    }
    return prevDiff.multiply(HUNDRED).divide(prevClose, 4, RoundingMode.HALF_UP);
  }

  /**
   * 기업행사 힌트가 있는 캔들인지: 락 구분 코드가 있거나(00 제외), 수정주가 플래그 Y, 분할비율 존재.
   */
  public static boolean hasCorporateActionHint(DailyPriceRow row) {
    boolean flng = row.flngClsCode() != null && !"00".equals(row.flngClsCode());
    boolean mod = "Y".equalsIgnoreCase(row.modYn());
    boolean split = row.splitRate() != null && row.splitRate().signum() != 0;
    return flng || mod || split;
  }

  private static String blankToNull(String value) {
    return StringUtils.isBlank(value) ? null : value.trim();
  }
}
