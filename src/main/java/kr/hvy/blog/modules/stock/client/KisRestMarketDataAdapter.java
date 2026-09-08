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
import kr.hvy.blog.modules.stock.client.dto.KisInvestorDailyResponse;
import kr.hvy.blog.modules.stock.client.dto.KisKsdInfoResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisStockInfoResponse;
import kr.hvy.blog.modules.stock.client.paginator.TrContPaginator;
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
  public static final String STOCK_INFO_PATH = "/uapi/domestic-stock/v1/quotations/search-stock-info";
  public static final String STOCK_INFO_TR_ID = "CTPF1002R";
  public static final String OVERSEAS_INDEX_PATH = "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice";
  public static final String OVERSEAS_INDEX_TR_ID = "FHKST03030100";
  public static final String OVERSEAS_DAILY_PATH = "/uapi/overseas-price/v1/quotations/dailyprice";
  public static final String OVERSEAS_DAILY_TR_ID = "HHDFS76240000";
  public static final String SHORT_SALE_PATH = "/uapi/domestic-stock/v1/quotations/daily-short-sale";
  public static final String ETF_NAV_PATH = "/uapi/etfetn/v1/quotations/nav-comparison-daily-trend";
  public static final String ETF_NAV_TR_ID = "FHPST02440200";
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
    List<KisHolidayResponse> pages = trContPaginator.paginate(HOLIDAY_PATH, HOLIDAY_TR_ID, params,
        KisHolidayResponse.class, context.withTarget("HOLIDAY"), maxPages, page -> {
          Map<String, String> next = new LinkedHashMap<>();
          next.put("CTX_AREA_FK", page.ctxAreaFk() == null ? "" : page.ctxAreaFk());
          next.put("CTX_AREA_NK", page.ctxAreaNk() == null ? "" : page.ctxAreaNk());
          return next;
        });
    List<KisHolidayResponse.Day> days = new ArrayList<>();
    for (KisHolidayResponse page : pages) {
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
    List<KisInvestorDailyResponse> pages = trContPaginator.paginate(INVESTOR_DAILY_PATH, INVESTOR_DAILY_TR_ID, params,
        KisInvestorDailyResponse.class, context.withTarget(ticker), Math.max(1, maxPages), page -> Map.of());
    List<KisInvestorDailyResponse.Row> rows = new ArrayList<>();
    for (KisInvestorDailyResponse page : pages) {
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

  @Override
  public List<Map<String, String>> fetchKsdInfo(KsdInfoKind kind, LocalDate from, LocalDate to, String ticker,
      int maxPages, KisCallContext context) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("CTS", "");
    params.put("F_DT", KisValues.format(from));
    params.put("T_DT", KisValues.format(to));
    params.put("SHT_CD", ticker == null ? "" : ticker);
    params.putAll(kind.getFixedParams());
    List<KisKsdInfoResponse> pages = trContPaginator.paginate(kind.getPath(), kind.getTrId(), params,
        KisKsdInfoResponse.class, context.withTarget(ticker == null ? kind.getCode() : ticker), maxPages, page -> Map.of());
    List<Map<String, String>> rows = new ArrayList<>();
    List<Map<String, String>> previous = null;
    for (KisKsdInfoResponse page : pages) {
      List<Map<String, String>> current = page.output1() == null ? List.of() : page.output1();
      if (previous != null && previous.equals(current)) {
        // 승계 키 없이 같은 페이지가 반복되면 더 볼 것이 없다
        log.debug("ksdinfo 동일 페이지 반복 → 중단: kind={}", kind);
        break;
      }
      rows.addAll(current);
      previous = current;
    }
    return rows;
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
