package kr.hvy.blog.modules.stock.client;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.client.dto.KisCreditBalanceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.dto.KisProgramTradeResponse;
import kr.hvy.blog.modules.stock.client.dto.KisShortSaleResponse;
import kr.hvy.blog.modules.stock.client.dto.KisOverseasDailyPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisOverseasIndexChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisHolidayResponse;
import kr.hvy.blog.modules.stock.client.dto.KisIndexChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisInvestorDailyResponse;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisStockInfoResponse;

/**
 * 시세·종목정보 조회 경계. 도메인 서비스는 REST 클라이언트를 직접 모르고 이 포트만 본다.
 * 2차(실시간 웹소켓) 도입 시 구현체를 추가하되 시그니처는 유지한다.
 */
public interface KisMarketDataPort {

  /**
   * 종목 일봉 [from, to] 최신 100건. originalPrice=true 면 원주가(FID_ORG_ADJ_PRC=1).
   */
  KisDailyChartResponse fetchDailyChart(String ticker, LocalDate from, LocalDate to, boolean originalPrice,
      KisCallContext context);

  /**
   * 종목 일봉 캔들만 (빈 응답은 빈 리스트).
   */
  default List<KisDailyChartResponse.Candle> fetchDailyCandles(String ticker, LocalDate from, LocalDate to,
      boolean originalPrice, KisCallContext context) {
    KisDailyChartResponse response = fetchDailyChart(ticker, from, to, originalPrice, context);
    return response.output2() == null ? List.of() : response.output2();
  }

  /**
   * 업종·시장 지수 일봉 [from, to] 최신 100건.
   */
  List<KisIndexChartResponse.Candle> fetchIndexCandles(String indexCode, LocalDate from, LocalDate to,
      KisCallContext context);

  /**
   * 기준일 이후 휴장일 정보 (연속조회 최대 maxPages 페이지).
   */
  List<KisHolidayResponse.Day> fetchHolidays(LocalDate baseDate, int maxPages, KisCallContext context);

  /**
   * 종목별 투자자매매동향(일별): 기준일 이하 최근 N일 (FHPTJ04160001). 페이지 수·소급 깊이는 실측 대상.
   */
  List<KisInvestorDailyResponse.Row> fetchInvestorDaily(String ticker, LocalDate baseDate, int maxPages,
      KisCallContext context);

  /**
   * 현재가 스냅샷 (시총·PER·PBR·52주·외인 소진율).
   */
  KisPriceResponse.Output fetchPrice(String ticker, KisCallContext context);

  /**
   * 예탁원정보 일정 [from, to]. ticker 가 null 이면 전 종목.
   */
  List<Map<String, String>> fetchKsdInfo(KsdInfoKind kind, LocalDate from, LocalDate to, String ticker, int maxPages,
      KisCallContext context);

  /**
   * 종목 기본정보 (상장일·상장폐지일·K200·업종).
   */
  KisStockInfoResponse.Output fetchStockInfo(String ticker, KisCallContext context);

  /**
   * 해외 지수(N)·환율(X) 일봉 [from, to] (FHKST03030100).
   */
  List<KisOverseasIndexChartResponse.Candle> fetchOverseasIndexCandles(String marketDiv, String symbol, LocalDate from,
      LocalDate to, KisCallContext context);

  /**
   * 해외 개별주·ETF 일봉: 기준일(BYMD) 이하 최근 100건 (HHDFS76240000, 수정주가 반영).
   */
  List<KisOverseasDailyPriceResponse.Candle> fetchOverseasDailyPrices(String exchange, String symbol, LocalDate baseDate,
      KisCallContext context);

  /**
   * 재무 API 1종 (연간 또는 분기) — 결산기별 행.
   */
  List<Map<String, String>> fetchFinancial(FinancialKind kind, String ticker, boolean quarterly, KisCallContext context);

  /**
   * 공매도 일별추이 [from, to] (FHPST04830000).
   */
  List<KisShortSaleResponse.Row> fetchShortSaleDaily(String ticker, LocalDate from, LocalDate to, KisCallContext context);

  /**
   * 신용잔고 일별추이: 기준일 이하 최근분 (FHPST04760000).
   */
  List<KisCreditBalanceResponse.Row> fetchCreditBalanceDaily(String ticker, LocalDate baseDate, KisCallContext context);

  /**
   * 종목별 프로그램매매 일별: 기준일 이하 최근분 (FHPPG04650201).
   */
  List<KisProgramTradeResponse.Row> fetchProgramTradeDaily(String ticker, LocalDate baseDate, KisCallContext context);

  /**
   * ETF NAV 비교추이(일) [from, to] 최신 100건 (FHPST02440200, 연속조회 없음).
   */
  List<KisEtfNavResponse.Row> fetchEtfNavDaily(String ticker, LocalDate from, LocalDate to, KisCallContext context);

  /**
   * 시장별 투자자매매동향(일별): 기준일 기준 (FHPTJ04040000, 연속조회 없음). 호출당 일수는 실측 항목.
   */
  List<KisMarketInvestorResponse.Row> fetchMarketInvestorDaily(MarketType market, LocalDate baseDate, KisCallContext context);
}
