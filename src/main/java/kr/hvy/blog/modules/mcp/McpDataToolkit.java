package kr.hvy.blog.modules.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.MetricColumn;
import kr.hvy.blog.modules.advisor.domain.code.ScreenOp;
import kr.hvy.blog.modules.advisor.application.service.SignalWeightMath;
import kr.hvy.blog.modules.advisor.repository.jdbc.*;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpDataToolkit {
  private final McpReadService reader;
  private final AdviceWriter advices;
  private final ScoreWriter scores;
  private final MorningCheckWriter morning;
  private final IntradayCheckWriter intraday;
  private final PickNoteRepository notes;
  private final SignalIcWriter ic;
  private final AdvisorProperties properties;
  private final kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService kpi;
  private final WeightSetRepository weightSets;
  private final StockLookupReader lookup;

  /** screenStocks 필터 수 상한·종목 수 상한 */
  static final int SCREEN_MAX_FILTERS = 5;
  static final int SCREEN_MAX = 50;

  /** screenStocks 필터 인자: column 은 metricTopN 과 같은 허용 컬럼, op 는 GTE|GT|LTE|LT(또는 >= > <= <), value 는 컬럼 단위 그대로 */
  public record ScreenFilterArg(
      @ToolParam(description = "지표 컬럼: ret_1d, ret_5d, ret_20d, ret_60d, ret_120d, dist_ma20, dist_ma60, dist_high_52w, tv_ratio_5_60, foreign_net_5d, institution_net_5d") String column,
      @ToolParam(description = "비교 연산자: GTE(>=), GT(>), LTE(<=), LT(<)") String op,
      @ToolParam(description = "기준값. 수익률·이격·비율은 소수(0.03 = 3%), 순매수는 원") double value) {
  }

  @Tool(name = "screenStocks", description = "다중 조건 스크리닝(metricTopN 과 같은 유니버스: 주권·활성·시총 1,000억·거래대금 10억 이상). filters 를 전부 만족하는 종목을 sort 컬럼 순으로 "
      + "최대 limit(기본 20, 최대 50)개. '거래대금 5/60 비율 3배 이상이면서 20일 수익률 3% 미만' 같은 질문용 — filters=[{column:tv_ratio_5_60,op:GTE,value:3},{column:ret_20d,op:LT,value:0.03}]. "
      + "값 단위는 컬럼 단위 그대로(수익률·이격·비율은 소수, 순매수는 원 — 억원 환산 없음). matched 는 조건 만족 총수, truncated 는 limit 에 잘렸는지. asOf 생략 시 마지막 지표일.")
  public Map<String, Object> screenStocks(
      @ToolParam(description = "필터 목록(1~5개, AND)") List<ScreenFilterArg> filters,
      @ToolParam(description = "정렬 컬럼(허용 컬럼 중 하나, 예: ret_20d)") String sort,
      @ToolParam(required = false, description = "desc(기본) 또는 asc") String order,
      @ToolParam(required = false, description = "KOSPI 또는 KOSDAQ, 생략하면 양시장") String market,
      @ToolParam(required = false, description = "종목 수 1~50 (기본 20)") Integer limit,
      @ToolParam(required = false, description = "기준 거래일 yyyy-MM-dd, 생략·미래면 마지막 지표일") String asOf) {
    if (filters == null || filters.isEmpty() || filters.size() > SCREEN_MAX_FILTERS) {
      throw new IllegalArgumentException("filters 는 1~" + SCREEN_MAX_FILTERS + "개");
    }
    MetricColumn sortColumn = MetricColumn.parse(sort).orElseThrow(() -> new IllegalArgumentException("지원하지 않는 정렬 컬럼 '" + sort + "'. 허용: " + MetricColumn.allowedList()));
    List<StockLookupReader.ScreenFilter> parsed = new ArrayList<>();
    for (ScreenFilterArg f : filters) {
      MetricColumn column = MetricColumn.parse(f.column()).orElseThrow(() -> new IllegalArgumentException("지원하지 않는 컬럼 '" + f.column() + "'. 허용: " + MetricColumn.allowedList()));
      ScreenOp op = ScreenOp.parse(f.op()).orElseThrow(() -> new IllegalArgumentException("지원하지 않는 연산자 '" + f.op() + "'. 허용: " + ScreenOp.allowedList()));
      if (!Double.isFinite(f.value())) throw new IllegalArgumentException("value 는 유한한 숫자");
      parsed.add(new StockLookupReader.ScreenFilter(column, op, f.value()));
    }
    String mkt = market == null || market.isBlank() ? null : market.trim().toUpperCase();
    if (mkt != null && !mkt.equals("KOSPI") && !mkt.equals("KOSDAQ")) throw new IllegalArgumentException("market 은 KOSPI 또는 KOSDAQ");
    boolean descending = order == null || !order.trim().equalsIgnoreCase("asc");
    int n = limit == null || limit <= 0 ? 20 : Math.min(limit, SCREEN_MAX);
    var date = lookup.latestMetricDate(McpReadService.end(asOf));
    if (date.isEmpty()) return Map.of("error", "no_data", "message", "지표가 없습니다");
    StockLookupReader.ScreenResult result = lookup.screen(parsed, sortColumn, descending, mkt, n, date.get());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("asOf", date.get().toString());
    m.put("filters", parsed.stream().map(f -> Map.of("column", f.column().getColumn(), "op", f.op().name(), "value", f.value())).toList());
    m.put("sort", sortColumn.getColumn());
    m.put("order", descending ? "desc" : "asc");
    if (mkt != null) m.put("market", mkt);
    m.put("matched", result.matched());
    m.put("n", result.rows().size());
    m.put("truncated", result.matched() > result.rows().size());
    Map<String, String> units = new LinkedHashMap<>();
    units.put(sortColumn.getColumn(), unit(sortColumn));
    parsed.forEach(f -> units.put(f.column().getColumn(), unit(f.column())));
    m.put("units", units);
    m.put("items", result.rows().stream().map(r -> {
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("tk", r.ticker());
      x.put("nm", r.name());
      x.put("mkt", r.market());
      x.put("v", r.values());
      return x;
    }).toList());
    return m;
  }

  /** 지표 컬럼 단위 표기: 순매수 금액은 원, 나머지(수익률·이격·비율)는 소수 비율 */
  private static String unit(MetricColumn column) {
    return column == MetricColumn.FOREIGN_NET_5D || column == MetricColumn.INSTITUTION_NET_5D ? "원" : "비율";
  }

  @Tool(description = "종목 마스터. 현재 상태이며 과거 상태를 보증하지 않는다. 먼저 resolveStock으로 확인한 ticker 사용.")
  public Map<String, Object> stockMaster(String ticker) { return reader.master(ticker); }

  @Tool(description = "종목 시계열. type: PRICE(수정주가), RAW_PRICE(원주가), METRIC, VALUATION, INVESTOR, MARKET_STAT, ETF_NAV(원가격), MASTER_HISTORY, ADJUST_EVENT. 기간 기본 최근 90일, limit 기본 50 최대 250. nextCursor를 같은 필터로 전달.")
  public Map<String, Object> stockSeries(String ticker, String type,
      @ToolParam(required = false) String from, @ToolParam(required = false) String to,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.series(true, type, ticker, from, to, null, limit, cursor);
  }
  @Tool(description = "재무 이력. available_from 및 first_seen_at KST 날짜가 asOf 이하인 행만, 최신 available_from 순(내림차순)으로 제공. limit 기본 50 최대 250. 금액·비율 단위는 stock://guide 확인.")
  public Map<String, Object> stockFinancials(String ticker, @ToolParam(required = false) String asOf,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.financials(ticker, asOf, limit, cursor);
  }
  @Tool(description = "기업행사 이력. effective_date 내림차순(최신순). from 생략 시 전체 이력, limit 기본 50 최대 250.")
  public Map<String, Object> stockCorporateActions(String ticker, @ToolParam(required = false) String from,
      @ToolParam(required = false) String to, @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.actions(ticker, from, to, limit, cursor);
  }
  @Tool(description = "기준일에 유효한 종목 섹터. 과거 구성 복원은 근사값이며 stock://guide 확인.")
  public Map<String, Object> stockSectors(String ticker, @ToolParam(required = false) String asOf,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.sectors(ticker, asOf, limit, cursor);
  }
  @Tool(description = "시장 시계열. type: INDEX, INDEX_METRIC, BREADTH, MARKET_INVESTOR, SECTOR, GLOBAL, MACRO, EVENT. code는 stock://catalog 확인. EVENT source 기본 LIVE, BACKFILL 명시 가능. MACRO 게시 지연 적용. 기간 기본 90일, limit 50 최대 250.")
  public Map<String, Object> marketSeries(String type, String code, @ToolParam(required = false) String from,
      @ToolParam(required = false) String to, @ToolParam(required = false) String source,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.series(false, type, code, from, to, source, limit, cursor);
  }
  @Tool(description = "저장된 advice 목록. kind 기본 DAILY, variant 기본 LIVE. 기간 기본 최근 90일. limit 기본 50 최대 250, 날짜·고유키 순 페이지.")
  public Map<String, Object> listAdvices(@ToolParam(required = false) String from, @ToolParam(required = false) String to,
      @ToolParam(required = false) String kind, @ToolParam(required = false) String variant,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.advices(from, to, kind, variant, limit, cursor);
  }
  @Tool(description = "advice ID 상세: 후보·픽·근거·점검·채점·노트. 0픽은 관망, 빈 채점은 아직 미채점. 프롬프트 원문은 제공하지 않는다.")
  public McpAdviceDetail adviceDetail(long adviceId) {
    var header = advices.findById(adviceId).orElseThrow(() -> new NoSuchElementException("판단이 없습니다"));
    return new McpAdviceDetail(McpAdviceHeader.from(header), advices.candidates(adviceId), advices.picks(adviceId), scores.candidateScores(adviceId),
        scores.callScores(adviceId), intraday.findByAdvice(adviceId), morning.findByAdvice(adviceId).orElse(null), notes.findByAdvice(adviceId));
  }
  @Tool(description = "advice ID의 채점. horizon 생략 시 그 판단 종류의 설정 호라이즌. 비어 있으면 미채점.")
  public Map<String, Object> adviceScores(long adviceId, @ToolParam(required = false) Integer horizon) {
    var header = advices.findById(adviceId).orElseThrow(() -> new NoSuchElementException("판단이 없습니다"));
    int h = horizon == null ? properties.horizonOf(header.adviceKind()).orElseThrow(() -> new IllegalArgumentException("설정에 없는 호라이즌")) : requireHorizon(horizon);
    return Map.of("adviceId", adviceId, "horizonDays", h,
        "candidateScores", scores.candidateScores(adviceId).stream().filter(r -> r.horizonDays() == h).toList(),
        "callScores", scores.callScores(adviceId).stream().filter(r -> r.horizonDays() == h).toList());
  }
  @Tool(description = "시그널 IC 통계. 기존 호라이즌별 ic-window 및 n_eff 계산. asOf 이하 거래일 window(기본 설정, 최대 250).")
  public Map<String, Object> signalIc(@ToolParam(required = false) String asOf, @ToolParam(required = false) Integer horizon,
      @ToolParam(required = false) Integer window) {
    int h = horizon == null ? properties.getHorizonDays() : requireHorizon(horizon);
    int w = window == null ? properties.icWindowDays(h) : Math.max(5, Math.min(250, window));
    return Map.of("requestedAsOf", McpReadService.end(asOf).toString(), "horizonDays", h, "window", w,
        "statistics", SignalWeightMath.aggregate(ic.window(h, McpReadService.end(asOf), w), h));
  }
  @Tool(description = "호라이즌별 가중치 세트와 시그널 가중치 이력. 기본 최근 90일, limit 50 최대 250.")
  public Map<String, Object> adviceWeights(@ToolParam(required = false) String from, @ToolParam(required = false) String to,
      @ToolParam(required = false) Integer horizon, @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) {
    return reader.weights(from, to, horizon == null ? properties.getHorizonDays() : requireHorizon(horizon), limit, cursor);
  }
  @Tool(description = "교훈 이력. 생성일 기간 기본 최근 90일, limit 50 최대 250.")
  public Map<String, Object> adviceLessons(@ToolParam(required = false) String from, @ToolParam(required = false) String to,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) { return reader.lessons(from, to, limit, cursor); }
  @Tool(description = "픽 노트 이력. 기준일 기간 기본 최근 90일, limit 50 최대 250. 원가격 필드 단위 원.")
  public Map<String, Object> adviceNotes(@ToolParam(required = false) String from, @ToolParam(required = false) String to,
      @ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor) { return reader.notes(from, to, limit, cursor); }

  @Tool(description = "데이터 해석 안내. topic: stock, advice, catalog. 리소스를 지원하지 않는 클라이언트에서도 사용 가능.")
  public Map<String, Object> dataGuide(String topic) {
    return switch (topic) {
      case "stock" -> Map.of("guide", McpConfig.STOCK_GUIDE);
      case "advice" -> Map.of("guide", McpConfig.ADVICE_GUIDE);
      case "catalog" -> reader.catalog();
      default -> throw new IllegalArgumentException("topic: stock, advice, catalog");
    };
  }

  @Tool(description = "지표 유무와 무관하게 데이터별·판단 종류별 최신 기준일을 확인. 다른 조회 전에 호출.")
  public Map<String, Object> dataFreshness() { return reader.freshness(); }

  @Tool(description = "판단 성과·표본 수·보정표·최근 픽. 기본 최근 90일, DAILY, 해당 종류의 설정 호라이즌. 미채점·판단 없음은 정상 빈 결과.")
  public kr.hvy.blog.modules.advisor.application.dto.ScoreSummaryResponse advicePerformance(
      @ToolParam(required = false) String from, @ToolParam(required = false) String to,
      @ToolParam(required = false) String kind, @ToolParam(required = false) Integer horizon) {
    var end = McpReadService.end(to);
    var start = start(from, end);
    var selected = kind == null ? kr.hvy.blog.modules.advisor.domain.code.AdviceKind.DAILY
        : kr.hvy.blog.modules.advisor.domain.code.AdviceKind.valueOf(kind);
    int h = horizon == null ? properties.horizonOf(selected).orElseThrow(() -> new IllegalArgumentException("설정에 없는 판단 종류"))
        : requireHorizon(horizon);
    return new kr.hvy.blog.modules.advisor.application.dto.ScoreSummaryResponse(start, end, h,
        kpi.variantSummaries(start, end, selected, h),
        kpi.regimeSummary(kr.hvy.blog.modules.advisor.domain.code.AdviceVariant.LIVE, start, end, selected, h),
        kpi.calibration(start, end, selected, h), kpi.recentPicks(start, end, 20, selected, h),
        "수익률은 비율. 표본 수·미채점·데이터 품질을 확인하세요. " + kpi.verdictLabel(selected), selected);
  }

  @Tool(description = "같은 기준일 MORNING과 DAILY 성과 대응 비교. 기본 최근 90일, 표본 수·평균차·표준오차 제공.")
  public kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService.MorningVsDaily morningPerformanceComparison(
      @ToolParam(required = false) String from, @ToolParam(required = false) String to) {
    var end = McpReadService.end(to);
    return kpi.morningVsDaily(start(from, end), end);
  }

  @Tool(description = "현재 활성 가중치. 이력 기간과 무관. horizon 기본 DAILY 설정. 없으면 active=null.")
  public Map<String, Object> activeAdviceWeights(@ToolParam(required = false) Integer horizon) {
    int h = horizon == null ? properties.getHorizonDays() : requireHorizon(horizon);
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("horizonDays", h);
    result.put("active", weightSets.active(h).orElse(null));
    return result;
  }

  private java.time.LocalDate start(String from, java.time.LocalDate end) {
    var start = from == null || from.isBlank() ? end.minusDays(90) : java.time.LocalDate.parse(from);
    if (start.isAfter(end)) throw new IllegalArgumentException("from must precede to");
    return start;
  }

  private int requireHorizon(int horizon) {
    if (!properties.getHorizons().containsKey(horizon)) throw new IllegalArgumentException("설정에 없는 호라이즌");
    return horizon;
  }
}
