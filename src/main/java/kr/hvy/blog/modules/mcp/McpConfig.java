package kr.hvy.blog.modules.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.advisor.application.chat.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpConfig {
  static final Set<String> READ_TOOLS = Set.of(
      "resolveStock", "stockSnapshot", "priceSeries", "metricTopN", "newsHeadlines",
      "marketOverview", "marketTrend", "marketRegime", "globalLink", "dataFreshness", "tradingDays",
      "latestAdvice", "horizonPicks", "compareAdvice", "adviceChecks", "screeningTop", "performanceSummary",
      "stockMaster", "stockSeries", "stockFinancials", "stockCorporateActions", "stockSectors", "marketSeries",
      "listAdvices", "adviceDetail", "adviceScores", "signalIc", "adviceWeights", "adviceLessons", "adviceNotes",
      "advicePerformance", "morningPerformanceComparison", "activeAdviceWeights", "dataGuide", "screenStocks");
  /** 도구별 statement_timeout(ms). 기본 5s. screeningTop 실시간 경로(그날 판단이 없거나 live=true)는 스크리닝 CTE 가 수 초라 15s (2026-10-16, P1-7) */
  static final Map<String, Integer> STATEMENT_TIMEOUT_MS = Map.of("screeningTop", 15_000);
  /** 도구별 트랜잭션 타임아웃(s). 기본 10s. statement_timeout 보다 길어야 DB 쪽 57014 가 먼저 나와 "timeout" 으로 분류된다 */
  static final Map<String, Integer> TRANSACTION_TIMEOUT_S = Map.of("screeningTop", 20);

  @Bean
  List<McpStatelessServerFeatures.SyncToolSpecification> mcpTools(StockToolkit stock, MarketToolkit market,
      CalendarToolkit calendar, AdviceToolkit advice, McpDataToolkit data, ObjectMapper mapper,
      PlatformTransactionManager manager, JdbcTemplate jdbc) {
    var callbacks = java.util.stream.Stream.concat(
        Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(stock, market, calendar, advice).build().getToolCallbacks())
            .filter(c -> !c.getToolDefinition().name().equals("dataFreshness")),
        Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(data).build().getToolCallbacks())).toArray(ToolCallback[]::new);
    var selected = Arrays.stream(callbacks).filter(c -> READ_TOOLS.contains(c.getToolDefinition().name())).toList();
    if (selected.size() != READ_TOOLS.size()) throw new IllegalStateException("MCP read tool allowlist mismatch");
    return selected.stream().map(callback -> {
      var definition = callback.getToolDefinition();
      TransactionTemplate tx = readTransaction(manager, TRANSACTION_TIMEOUT_S.getOrDefault(definition.name(), 10));
      var schema = mapper.readValue(definition.inputSchema(), new TypeReference<Map<String, Object>>() {});
      var tool = McpSchema.Tool.builder(definition.name(), schema).description(definition.description())
          .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, false)).build();
      return new McpStatelessServerFeatures.SyncToolSpecification(tool,
          (context, request) -> call(callback, request.arguments(), mapper, tx, jdbc));
    }).toList();
  }

  static TransactionTemplate readTransaction(PlatformTransactionManager manager) {
    return readTransaction(manager, 10);
  }

  static TransactionTemplate readTransaction(PlatformTransactionManager manager, int timeoutSeconds) {
    var tx = new TransactionTemplate(manager);
    tx.setReadOnly(true);
    tx.setTimeout(timeoutSeconds);
    return tx;
  }

  static McpSchema.CallToolResult call(ToolCallback callback, Map<String, Object> args, ObjectMapper mapper,
      TransactionTemplate tx, JdbcTemplate jdbc) {
    long started = System.nanoTime();
    String name = callback.getToolDefinition().name();
    Map<String, Object> result;
    try {
      validateArguments(args);
      result = tx.execute(status -> {
        jdbc.execute("SET TRANSACTION READ ONLY");
        jdbc.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS.getOrDefault(name, 5000));
        ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(TRANSACTION_TIMEOUT_S.getOrDefault(name, 10)));
        var context = new java.util.HashMap<>(scope.toToolContext());
        context.put("mcp", true);
        String json = callback.call(mapper.writeValueAsString(args == null ? Map.of() : args), new ToolContext(context));
        Map<String, Object> body = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        // Existing Slack tools retain their compact payload; MCP adds explicit request/data dates.
        for (String field : List.of("asOf", "baseDate", "to")) {
          if (args != null && args.get(field) != null) {
            body.putIfAbsent("requestedAsOf", args.get(field));
            body.putIfAbsent("queryTo", McpReadService.end(args.get(field).toString()).toString());
            break;
          }
        }
        scope.earliestAsOf().ifPresent(date -> body.putIfAbsent("dataAsOf", date.toString()));
        return body;
      });
      if (result == null) result = Map.of("error", "no_data", "message", "데이터가 없습니다");
      if ("internal".equals(result.get("error"))) result = Map.of("error", "internal", "message", "조회 중 오류가 발생했습니다");
    } catch (Exception e) {
      String code = errorCode(e);
      result = Map.of("error", code, "message", switch (code) {
        case "bad_argument" -> "입력값을 확인하세요";
        case "not_found" -> "요청한 데이터가 없습니다";
        case "timeout" -> "조회 제한 시간을 초과했습니다";
        default -> "조회 중 오류가 발생했습니다";
      });
    }
    if ("no_data".equals(result.get("error"))) {
      result = new java.util.LinkedHashMap<>(result);
      result.remove("error");
      result.put("items", List.of());
      result.put("status", "no_data");
    }
    boolean error = result.containsKey("error");
    log.info("mcp tool={} durationMs={} status={} traceId={}", name, (System.nanoTime() - started) / 1_000_000,
        error ? "error" : "ok", MDC.get("traceId"));
    String json = mapper.writeValueAsString(result);
    return McpSchema.CallToolResult.builder(List.of(McpSchema.TextContent.builder(json).build()))
        .structuredContent(result).isError(error).build();
  }

  static void validateArguments(Map<String, Object> args) {
    if (args == null) return;
    for (String field : List.of("from", "to", "asOf", "baseDate")) {
      Object value = args.get(field);
      if (value != null && !value.toString().isBlank()) java.time.LocalDate.parse(value.toString());
    }
    if (args.get("from") != null && args.get("to") != null
        && !args.get("from").toString().isBlank() && !args.get("to").toString().isBlank()
        && java.time.LocalDate.parse(args.get("from").toString()).isAfter(java.time.LocalDate.parse(args.get("to").toString())))
      throw new IllegalArgumentException("from must precede to");
  }

  static String errorCode(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.util.NoSuchElementException) return "not_found";
      if (cause instanceof org.springframework.dao.QueryTimeoutException
          || cause instanceof org.springframework.transaction.TransactionTimedOutException
          || cause instanceof java.util.concurrent.TimeoutException
          || cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) return "timeout";
      if (cause instanceof IllegalArgumentException || cause instanceof java.time.format.DateTimeParseException
          || cause instanceof tools.jackson.core.JacksonException) return "bad_argument";
      if (cause.getCause() == cause) break;
    }
    return "internal";
  }

  @Bean
  List<McpStatelessServerFeatures.SyncResourceSpecification> mcpResources(McpReadService reader,
      ObjectMapper mapper, PlatformTransactionManager manager, JdbcTemplate jdbc) {
    return List.of(resource("stock://guide", "Stock data guide", () -> STOCK_GUIDE),
        resource("advice://guide", "Advice data guide", () -> ADVICE_GUIDE),
        resource("stock://catalog", "Stock data catalog", () -> readTransaction(manager).execute(s -> {
          jdbc.execute("SET TRANSACTION READ ONLY");
          jdbc.execute("SET LOCAL statement_timeout = 5000");
          return mapper.writeValueAsString(reader.catalog());
        })));
  }
  private McpStatelessServerFeatures.SyncResourceSpecification resource(String uri, String name,
      java.util.function.Supplier<String> body) {
    return new McpStatelessServerFeatures.SyncResourceSpecification(
        McpSchema.Resource.builder(uri, name).mimeType("text/plain").build(),
        (context, request) -> {
          try {
            return McpSchema.ReadResourceResult.builder(List.of(
                McpSchema.TextResourceContents.builder(uri, body.get()).mimeType("text/plain").build())).build();
          } catch (RuntimeException e) {
            throw new IllegalStateException("리소스를 조회할 수 없습니다");
          }
        });
  }
  static final String STOCK_GUIDE = """
      먼저 dataFreshness, 다음 resolveStock으로 코드를 확인하세요. 날짜는 yyyy-MM-dd, 국내 시점은 KST.
      기존 도구 asOf는 실제 수집 기준일, requestedAsOf는 요청 날짜, queryTo는 미래 날짜를 오늘로 제한한 조회 상한, dataAsOf는 해당 페이지 마지막 관측 날짜.
      목록은 날짜와 고유키 오름차순(stockFinancials·stockCorporateActions는 최신순 내림차순, 응답 order 키로 구분), 기본 50 최대 250. nextCursor는 동일 필터로 전달. 종료 시 null.
      stockSeries PRICE는 vw_stock_daily_price_adj: adj_* 수정가격(원), raw_close 원종가(원), raw_volume 원거래량(주).
      단위 접미(2026-10-16~): _won 원, _eok 억원, _thou 천주, _shr 주, _pct %(1=1%). 같은 이름이 소스마다 단위가 달라 응답 키에 붙였습니다(DB 컬럼명은 그대로, 필터 인자는 바뀌지 않음).
      METRIC 가격·수익률은 수정주가 기반. ret_*·dist_*는 비율(0.01=1%), tv_*·net_* 금액은 원(screenStocks 컬럼명과 같아 접미 없음).
      VALUATION market_cap_won·eps_won·bps_won 원, listed_shares_shr 주, PER/PBR 배(0 이 아니라 null 이 결측·산출 불가), foreign_hold_rate_pct %. ETF_NAV close_price 원가격(원), nav 원, disparity_rate_pct %.
      stockMaster·MASTER_HISTORY listed_shares_thou 천주(주 단위는 VALUATION listed_shares_shr), capital_won·par_value_won 원. stockSnapshot 은 *Eok 억원·foreignHoldRatePct %.
      INDEX·INDEX_METRIC 지수 포인트, ret_* 비율. BREADTH *_ratio는 비율. MASTER_HISTORY는 valid_from 기준 변경 이력, ADJUST_EVENT의 *_factor는 배수. INDEX 지수 포인트. GLOBAL 가격은 원천 통화/포인트이며 symbol·market_div로 구분.
      *_pct 접미 필드(change_rate_pct·prtt_rate_pct·bench_rate_pct·avg_change_rate_pct·disparity_rate_pct·foreign_hold_rate_pct·재무 roe_pct·debt_ratio_pct 등)는 %(1=1%)이고, 접미 없는 ret_*·dist_*·*_ratio·excess_rate 같은 수익률·비율은 소수(0.01=1%)입니다.
      INVESTOR *_net_amt_won 원, *_net_qty_shr 주, null은 결측, 0은 실제 0입니다.
      종목 5일·업종 수급 합계는 복구된 비결측 값만 집계하므로 부분 복구 기간은 전체 합계로 해석하지 마세요.
      MARKET_INVESTOR *_net_amt_won 원(KIS 백만원 ×1e6), *_net_qty_thou 천주(KIS 원천 그대로), null은 결측, 0은 실제 0입니다.
      stockFinancials는 available_from와 first_seen_at KST 날짜 모두 기준일 이하인 행만 제공. 재무 금액은 억원(*_eok — 삼성전자 202512 revenue_eok 3,336,059 = 333.6조), 비율은 %(*_pct).
      Q 손익은 회계연도 누적(YTD: 12월 결산 202603=1분기, 202606=상반기), Y 는 결산월 행만(2026-10-16 정리 — 그 전엔 연간 응답에 붙은 진행 중 회계연도 중간기간이 Y 로 섞였다).
      재무 비율 0.00(KIS 공란)은 null 이고 borrowing_dependency_pct 만 0(무차입 또는 결측)을 유지합니다.
      재무·거시 정정 이력이 완전한 당시 버전을 보장하지는 않습니다. MACRO는 available_from<=to 및 obs_date<to로 게시 지연 제한.
      VIX 포인트, UST10Y/UST2Y %. EVENT는 완결된 UTC 관측일, 기본 LIVE, BACKFILL은 재구성 값으로 구분.
      SECTOR 파생 시계열은 현재 구성으로 과거를 근사합니다(sum_market_cap_won·sum_trading_value_won·foreign_net_sum_won 원). stockSectors는 저장된 유효기간 기준이며 과거 완전 복원을 보장하지 않습니다.
      KRX 섹터 = 지수업종 중분류, 중분류가 없으면 대분류(비제조업 — 0019 운송·창고, 0021 금융 등, 2026-10-16). KOSDAQ 일부는 분류 코드가 없어 미분류(null)입니다.
      SECTOR 목록은 활성 종목이 속한 업종만 포함하며(업종 지수가 있어도 소속 종목이 없으면 빠짐), INDEX·INDEX_METRIC·SECTOR 응답의 codeName 과 카탈로그 name·active 로 업종명을 확인하세요.
      누락값은 null 또는 필드 없음이며 0으로 해석하지 마세요. 주가 수정계수는 현재 뷰 기준으로 과거 시점의 계수 버전을 보장하지 않습니다.
      원천: 국내 KIS/KRX, 글로벌 KIS, 거시 CBOE/TREASURY, 사건 GDELT. 상세 필드 의미·단위·코드는 stock://catalog 확인.
      """;
  static final String ADVICE_GUIDE = """
      listAdvices 기본 DAILY/LIVE, 최근 90일, 기본 summary=true(헤더+pick_count; JSON 블록은 summary=false 또는 adviceDetail). 다른 종류로 대체하지 않습니다. 0픽은 관망, 빈 채점은 미채점입니다.
      adviceDetail: header 기준일·실제 dataAsOf, candidates 동결 정량 후보·signals, picks 근거·위험·인용,
      candidateScores/callScores 사후 채점, morningCheck/intradayChecks 점검, notes 오답노트.
      성과 가격은 수정주가, 수익률·excessRet은 비율(0.01=1%). 방향·stage·status를 함께 읽으세요.
      adviceNotes: change_rate_pct·bench_rate_pct는 %(1.2=1.2%), gap_rate·since_open_rate·excess_rate·final_excess는 소수 비율, z_score는 σ 배수(반나절 σ 기준).
      morningCheck 임계(threshold)는 2026-10-16부터 밤사이 갭 σ(detail.index.*.sigmaBasis=GAP), 그 전 행은 종가 σ_1d(키 없음)라 HOLD 가 거의 자동 적중입니다 — performanceSummary morning.byBasis(CLOSE·GAP·FALLBACK)로 나눠 읽으세요.
      morningCheck detail.usClosed=true 는 판단 기준일 날짜의 미국 새 세션이 없었다(usDate < 기준일, 미국 휴장)는 뜻이며 "미국 장 마감" 이 아닙니다. MORNING diff_json.usClosed 는 미국 데이터 결측도 포함합니다.
      intradayChecks agreement 는 2026-10-16부터 지수 대비 초과가 양인 픽 비율(agreementBasis EXCESS, 픽 JSON agreeBasis OPEN|PREV_CLOSE), 그 전 행은 전일 대비 상승 픽 비율(ABSOLUTE)입니다. 지수 방향 적중은 comment·index_json 에 따로 있습니다.
      adviceScores 기본 호라이즌은 advisor.horizons의 해당 판단 종류. signalIc는 기존 IC 창 및 n_eff=IC일수/호라이즌 계산(RS_INDEX 는 2026-10-16 계산 대상에서 빠짐).
      adviceWeights·activeAdviceWeights 의 as_of 는 생성일이 아니라 IC 창의 마지막 실현 기준일(d+h 수익률 확정)이고 생성 시각은 created_at(createdAt)입니다. 2026-10-16 이후 세트: 배수 하한 0(음의 IC 는 가중치 0, 부호 반전 없음), IC 로 검증된 시그널끼리만 재정규화하고 VALUE_RANK·IC 없음은 사전 가중치 고정, RS_INDEX 비활성(KOSPI 단일 유니버스에서 MOM_20D 와 순위 동일). 그 전 세트는 하한 0.5·활성 전체 재정규화.
      screeningTop source=STORED 는 그날 판단이 쓴 세트(weightSetId)의 후보, 현재 세트와 다르면 activeWeightSetId 가 함께 옵니다. 현재 세트 순위는 live=true. performanceSummary variants[].byWeightSet 은 기간 안에 세트가 섞였을 때 세트별 픽 KPI 입니다.
      regime themes[].basis=EQ(2026-10-16~, advice-v11)면 strength 는 동일가중 벤치(KOSPI200 전 구성 종목 중앙값) 대비 rsEq20/rsEq60 기준, basis 가 없으면 KOSPI 대비 rs20/rs60 기준입니다. name 은 설정 매핑입니다.
      2026-10-16 배포 전에 만든 판단(advice_id ≤ 78)의 후보 features 는 수정 전 값입니다: 대형주 marketCap 9.223372036854775E12 포화, rsIdx20d = 지수 20일 수익률(전 후보 동일), FOREIGN_FLOW·INST_FLOW raw 0.0(수급이 백만원이던 시기), SECTOR_STRENGTH '0000' 미분류 묶음. 수치 비교에 쓰지 마세요.
      data_as_of_json.flowProvisional 은 2026-10-16부터 계산값(뒤 DAILY 가 시장 수급을 다시 받았으면 false)이고 그 전 행은 항상 true 였습니다.
      adviceNotes z_score 는 2026-10-16 12:00 점검까지 null 입니다(vol20d 키 결함, 소급 없음) — 그래서 그때까지 note_class 가 전부 FLAT 이고 회고(why·hypothesis)가 없습니다. status OPEN 은 청산일(T+5) 전이라는 뜻입니다.
      adviceWeights는 가중치 세트·시그널 가중치 이력, adviceLessons는 근거·조건·상태와 gate(누적 DAILY LIVE 픽 ≥ minPicks 일 때만 교훈 생성 — active=false 면 0건이 정상), adviceNotes 가격은 원가격(원).
      성과·통계는 표본 수와 데이터 품질을 함께 확인하세요. H60/H180 판정 한계는 기존 설정을 따릅니다.
      수집 실행·새 판단 생성·삭제·설정 변경·토큰·채팅·프롬프트 원문은 제공하지 않습니다. 투자 자문이 아닌 개인 실험입니다.
      """;
}
