package kr.hvy.blog.modules.stock.client;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.dto.KisCreditBalanceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.dto.KisProgramTradeResponse;
import kr.hvy.blog.modules.stock.client.dto.KisShortSaleResponse;
import kr.hvy.blog.modules.stock.client.dto.KisFinancialResponse;
import kr.hvy.blog.modules.stock.client.dto.KisOverseasDailyPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisOverseasIndexChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisHolidayResponse;
import kr.hvy.blog.modules.stock.client.dto.KisIndexChartResponse;
import kr.hvy.blog.modules.stock.client.dto.KisIndexPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisInvestorDailyResponse;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.client.dto.KisKsdInfoResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisStockInfoResponse;
import kr.hvy.blog.modules.stock.client.dto.KsdInfoPage;
import kr.hvy.blog.modules.stock.client.paginator.PageResult;
import kr.hvy.blog.modules.stock.client.paginator.TrContPaginator;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link KisMarketDataPort} 의 REST 구현. 경로·tr_id·파라미터 이름은 KIS 공식 예제(examples_llm)와 같고,
 * 응답 필드는 KIS Code Assistant MCP 의 column_mapping 을 따른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisRestMarketDataAdapter implements KisMarketDataPort {

  public static final String DAILY_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
  public static final String DAILY_CHART_TR_ID = "FHKST03010100";
  public static final String INDEX_CHART_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
  public static final String INDEX_CHART_TR_ID = "FHKUP03500100";
  public static final String HOLIDAY_PATH = "/uapi/domestic-stock/v1/quotations/chk-holiday";
  public static final String HOLIDAY_TR_ID = "CTCA0903R";
  public static final String INVESTOR_DAILY_PATH = "/uapi/domestic-stock/v1/quotations/investor-trade-by-stock-daily";
  public static final String INVESTOR_DAILY_TR_ID = "FHPTJ04160001";
  public static final String PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
  public static final String PRICE_TR_ID = "FHKST01010100";
  /** 국내업종 현재지수 (advisor 장중 점검). TR ID 는 KIS 문서 기준이며 KisIndexPriceManualTest 로 실측한다 (2026-09-13) */
  public static final String INDEX_PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-index-price";
  public static final String INDEX_PRICE_TR_ID = "FHPUP02100000";
  public static final String STOCK_INFO_PATH = "/uapi/domestic-stock/v1/quotations/search-stock-info";
  public static final String STOCK_INFO_TR_ID = "CTPF1002R";
  public static final String OVERSEAS_INDEX_PATH = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
  public static final String OVERSEAS_INDEX_TR_ID = "FHKST03030100";
  public static final String OVERSEAS_DAILY_PATH = "/uapi/overseas-price/v1/quotations/dailyprice";
  public static final String OVERSEAS_DAILY_TR_ID = "HHDFS76240000";
  public static final String SHORT_SALE_PATH = "/uapi/domestic-stock/v1/quotations/daily-short-sale";
  public static final String ETF_NAV_PATH = "/uapi/etfetn/v1/quotations/nav-comparison-daily-trend";
  public static final String ETF_NAV_TR_ID = "FHPST02440200";
  /** 경로는 공식 예제 디렉터리명(inquire_investor_daily_by_market)에서 추정 — 실측 항목 */
  public static final String MARKET_INVESTOR_PATH = "/uapi/domestic-stock/v1/quotations/inquire-investor-daily-by-market";
  public static final String MARKET_INVESTOR_TR_ID = "FHPTJ04040000";
  private static final String MARKET_SECTOR = "U";
  public static final String SHORT_SALE_TR_ID = "FHPST04830000";
  public static final String CREDIT_BALANCE_PATH = "/uapi/domestic-stock/v1/quotations/daily-credit-balance";
  public static final String CREDIT_BALANCE_TR_ID = "FHPST04760000";
  public static final String PROGRAM_TRADE_PATH = "/uapi/domestic-stock/v1/quotations/program-trade-by-stock-daily";
  public static final String PROGRAM_TRADE_TR_ID = "FHPPG04650201";
  /** 신용잔고 일별추이 화면 분류 코드 (공식 예제 고정값) */
  private static final String CREDIT_SCREEN_CODE = "20476";

  /** 시장 구분: J 주식(KRX), U 업종 */
  private static final String MARKET_STOCK = "J";
  private static final String MARKET_INDEX = "U";
  private static final String PERIOD_DAILY = "D";
  /** 주식기본조회 상품유형: 300 주식·ETF·ETN·ELW */
  private static final String PRODUCT_TYPE_STOCK = "300";

  private final KisApiClient apiClient;
  private final TrContPaginator trContPaginator;
  private final KisProperties kisProperties;

  @Override
  public KisDailyChartResponse fetchDailyChart(String ticker, LocalDate from, LocalDate to, boolean originalPrice,
      KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_INPUT_ISCD", ticker);
    params.put("FID_INPUT_DATE_1", KisValues.format(from));
    params.put("FID_INPUT_DATE_2", KisValues.format(to));
    params.put("FID_PERIOD_DIV_CODE", PERIOD_DAILY);
    params.put("FID_ORG_ADJ_PRC", originalPrice ? "1" : "0");
    return apiClient.get(DAILY_CHART_PATH, DAILY_CHART_TR_ID, params, KisDailyChartResponse.class,
        context.withTarget(ticker)).body();
  }

  @Override
  public List<KisIndexChartResponse.Candle> fetchIndexCandles(String indexCode, LocalDate from, LocalDate to,
      KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_INDEX);
    params.put("FID_INPUT_ISCD", indexCode);
    params.put("FID_INPUT_DATE_1", KisValues.format(from));
    params.put("FID_INPUT_DATE_2", KisValues.format(to));
    params.put("FID_PERIOD_DIV_CODE", PERIOD_DAILY);
    KisIndexChartResponse body = apiClient.get(INDEX_CHART_PATH, INDEX_CHART_TR_ID, params,
        KisIndexChartResponse.class, context.withTarget(indexCode)).body();
    return body.output2() == null ? List.of() : body.output2();
  }

  @Override
  public List<KisHolidayResponse.Day> fetchHolidays(LocalDate baseDate, int maxPages, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("BASS_DT", KisValues.format(baseDate));
    params.put("CTX_AREA_FK", "");
    params.put("CTX_AREA_NK", "");
    PageResult<KisHolidayResponse> result = trContPaginator.paginate(HOLIDAY_PATH, HOLIDAY_TR_ID, params,
        KisHolidayResponse.class, context.withTarget("HOLIDAY"), maxPages, page -> {
          Map<String, String> next = new LinkedHashMap<>();
          next.put("CTX_AREA_FK", page.ctxAreaFk() == null ? "" : page.ctxAreaFk());
          next.put("CTX_AREA_NK", page.ctxAreaNk() == null ? "" : page.ctxAreaNk());
          return next;
        });
    if (result.truncated()) {
      log.debug("휴장일 연속조회 {}페이지 상한 도달 — 의도된 창(1페이지 ≈ 1개월)이라 잘림이 아니다", maxPages);
    }
    List<KisHolidayResponse.Day> days = new ArrayList<>();
    for (KisHolidayResponse page : result.pages()) {
      if (page.output() != null) {
        days.addAll(page.output());
      }
    }
    return days;
  }

  @Override
  public List<KisInvestorDailyResponse.Row> fetchInvestorDaily(String ticker, LocalDate baseDate, int maxPages,
      KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_INPUT_ISCD", ticker);
    params.put("FID_INPUT_DATE_1", KisValues.format(baseDate));
    params.put("FID_ORG_ADJ_PRC", "");
    params.put("FID_ETC_CLS_CODE", "");
    PageResult<KisInvestorDailyResponse> result = trContPaginator.paginate(INVESTOR_DAILY_PATH, INVESTOR_DAILY_TR_ID,
        params, KisInvestorDailyResponse.class, context.withTarget(ticker), Math.max(1, maxPages), page -> Map.of());
    // 상한(백필 5·증분 1)은 날짜 창의 크기다. 남은 과거는 다음 윈도우(체크포인트 커서)가 받으므로 잘림이 아니다.
    List<KisInvestorDailyResponse.Row> rows = new ArrayList<>();
    for (KisInvestorDailyResponse page : result.pages()) {
      if (page.output2() != null) {
        rows.addAll(page.output2());
      }
    }
    return rows;
  }

  @Override
  public KisPriceResponse.Output fetchPrice(String ticker, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_INPUT_ISCD", ticker);
    return apiClient.get(PRICE_PATH, PRICE_TR_ID, params, KisPriceResponse.class, context.withTarget(ticker))
        .body().output();
  }

  /**
   * 업종·시장 지수 현재가 (FID_COND_MRKT_DIV_CODE=U). 장중 점검이 호출당 1건씩 쓰며 리미터 간격을 그대로 따른다.
   */
  @Override
  public KisIndexPriceResponse.Output fetchIndexPrice(String indexCode, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_INDEX);
    params.put("FID_INPUT_ISCD", indexCode);
    return apiClient.get(INDEX_PRICE_PATH, INDEX_PRICE_TR_ID, params, KisIndexPriceResponse.class, context.withTarget(indexCode))
        .body().output();
  }

  /**
   * 예탁원정보 한 기간. CTS 승계 없이 tr_cont=N 으로만 넘기며, 같은 페이지가 반복되면 즉시 멈춘다(페이지 단위 감지라 호출을 낭비하지 않는다).
   * 상한 도달은 잘림이므로 {@link KsdInfoPage#truncated()} 로 알려 호출부가 기간을 나누게 한다 (2026-09-09 이전엔 조용히 잘렸다).
   */
  @Override
  public KsdInfoPage fetchKsdInfo(KsdInfoKind kind, LocalDate from, LocalDate to, String ticker,
      int maxPages, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("CTS", "");
    params.put("F_DT", KisValues.format(from));
    params.put("T_DT", KisValues.format(to));
    params.put("SHT_CD", ticker == null ? "" : ticker);
    params.putAll(kind.getFixedParams());
    PageResult<KisKsdInfoResponse> result = trContPaginator.paginate(kind.getPath(), kind.getTrId(), params,
        KisKsdInfoResponse.class, context.withTarget(ticker == null ? kind.getCode() : ticker), maxPages, page -> Map.of(),
        (previous, current) -> Objects.equals(previous.output1(), current.output1()));
    List<Map<String, String>> rows = new ArrayList<>();
    for (KisKsdInfoResponse page : result.pages()) {
      if (page.output1() != null) {
        rows.addAll(page.output1());
      }
    }
    return new KsdInfoPage(rows, result.truncated(), result.repeated(), result.pageCount());
  }

  @Override
  public KisStockInfoResponse.Output fetchStockInfo(String ticker, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("PRDT_TYPE_CD", PRODUCT_TYPE_STOCK);
    params.put("PDNO", ticker);
    return apiClient.get(STOCK_INFO_PATH, STOCK_INFO_TR_ID, params, KisStockInfoResponse.class,
        context.withTarget(ticker)).body().output();
  }

  @Override
  public List<KisOverseasIndexChartResponse.Candle> fetchOverseasIndexCandles(String marketDiv, String symbol,
      LocalDate from, LocalDate to, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", marketDiv);
    params.put("FID_INPUT_ISCD", symbol);
    params.put("FID_INPUT_DATE_1", KisValues.format(from));
    params.put("FID_INPUT_DATE_2", KisValues.format(to));
    params.put("FID_PERIOD_DIV_CODE", PERIOD_DAILY);
    KisOverseasIndexChartResponse body = apiClient.get(OVERSEAS_INDEX_PATH, OVERSEAS_INDEX_TR_ID, params,
        KisOverseasIndexChartResponse.class, context.withTarget(symbol)).body();
    return body.output2() == null ? List.of() : body.output2();
  }

  @Override
  public List<KisOverseasDailyPriceResponse.Candle> fetchOverseasDailyPrices(String exchange, String symbol,
      LocalDate baseDate, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("AUTH", "");
    params.put("EXCD", exchange);
    params.put("SYMB", symbol);
    params.put("GUBN", "0");
    params.put("BYMD", KisValues.format(baseDate));
    params.put("MODP", "1");
    KisOverseasDailyPriceResponse body = apiClient.get(OVERSEAS_DAILY_PATH, OVERSEAS_DAILY_TR_ID, params,
        KisOverseasDailyPriceResponse.class, context.withTarget(symbol)).body();
    return body.output2() == null ? List.of() : body.output2();
  }

  @Override
  public List<Map<String, String>> fetchFinancial(FinancialKind kind, String ticker, boolean quarterly,
      KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_DIV_CLS_CODE", quarterly ? "1" : "0");
    params.put("fid_cond_mrkt_div_code", MARKET_STOCK);
    params.put("fid_input_iscd", ticker);
    KisFinancialResponse body = apiClient.get(kind.getPath(), kind.getTrId(), params, KisFinancialResponse.class,
        context.withTarget(ticker)).body();
    return body.output() == null ? List.of() : body.output();
  }

  @Override
  public List<KisMarketInvestorResponse.Row> fetchMarketInvestorDaily(MarketType market, LocalDate baseDate, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_SECTOR);
    params.put("FID_INPUT_ISCD", market.getCompositeIndexCode());
    params.put("FID_INPUT_DATE_1", KisValues.format(baseDate));
    params.put("FID_INPUT_ISCD_1", market.getInvestorMarketCode());
    params.put("FID_INPUT_DATE_2", KisValues.format(baseDate));
    params.put("FID_INPUT_ISCD_2", market.getCompositeIndexCode());
    KisMarketInvestorResponse body = apiClient.get(MARKET_INVESTOR_PATH, MARKET_INVESTOR_TR_ID, params,
        KisMarketInvestorResponse.class, context.withTarget(market.getCode())).body();
    return body.output() == null ? List.of() : body.output();
  }

  @Override
  public List<KisEtfNavResponse.Row> fetchEtfNavDaily(String ticker, LocalDate from, LocalDate to, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_INPUT_ISCD", ticker);
    params.put("FID_INPUT_DATE_1", KisValues.format(from));
    params.put("FID_INPUT_DATE_2", KisValues.format(to));
    KisEtfNavResponse body = apiClient.get(ETF_NAV_PATH, ETF_NAV_TR_ID, params, KisEtfNavResponse.class,
        context.withTarget(ticker)).body();
    return body.output() == null ? List.of() : body.output();
  }

  @Override
  public List<KisShortSaleResponse.Row> fetchShortSaleDaily(String ticker, LocalDate from, LocalDate to, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_INPUT_ISCD", ticker);
    params.put("FID_INPUT_DATE_1", KisValues.format(from));
    params.put("FID_INPUT_DATE_2", KisValues.format(to));
    KisShortSaleResponse body = apiClient.get(SHORT_SALE_PATH, SHORT_SALE_TR_ID, params, KisShortSaleResponse.class,
        context.withTarget(ticker)).body();
    return body.output2() == null ? List.of() : body.output2();
  }

  @Override
  public List<KisCreditBalanceResponse.Row> fetchCreditBalanceDaily(String ticker, LocalDate baseDate, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_COND_SCR_DIV_CODE", CREDIT_SCREEN_CODE);
    params.put("FID_INPUT_ISCD", ticker);
    params.put("FID_INPUT_DATE_1", KisValues.format(baseDate));
    KisCreditBalanceResponse body = apiClient.get(CREDIT_BALANCE_PATH, CREDIT_BALANCE_TR_ID, params,
        KisCreditBalanceResponse.class, context.withTarget(ticker)).body();
    return body.output() == null ? List.of() : body.output();
  }

  @Override
  public List<KisProgramTradeResponse.Row> fetchProgramTradeDaily(String ticker, LocalDate baseDate, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("FID_COND_MRKT_DIV_CODE", MARKET_STOCK);
    params.put("FID_INPUT_ISCD", ticker);
    params.put("FID_INPUT_DATE_1", KisValues.format(baseDate));
    KisProgramTradeResponse body = apiClient.get(PROGRAM_TRADE_PATH, PROGRAM_TRADE_TR_ID, params,
        KisProgramTradeResponse.class, context.withTarget(ticker)).body();
    return body.output() == null ? List.of() : body.output();
  }
}
