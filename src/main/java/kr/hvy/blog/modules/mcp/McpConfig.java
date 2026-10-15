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
      "advicePerformance", "morningPerformanceComparison", "activeAdviceWeights", "dataGuide");

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
    TransactionTemplate tx = readTransaction(manager);
    return selected.stream().map(callback -> {
      var definition = callback.getToolDefinition();
      var schema = mapper.readValue(definition.inputSchema(), new TypeReference<Map<String, Object>>() {});
      var tool = McpSchema.Tool.builder(definition.name(), schema).description(definition.description())
          .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, false)).build();
      return new McpStatelessServerFeatures.SyncToolSpecification(tool,
          (context, request) -> call(callback, request.arguments(), mapper, tx, jdbc));
    }).toList();
  }

  static TransactionTemplate readTransaction(PlatformTransactionManager manager) {
    var tx = new TransactionTemplate(manager);
    tx.setReadOnly(true);
    tx.setTimeout(10);
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
        jdbc.execute("SET LOCAL statement_timeout = 5000");
        ChatRequestScope scope = new ChatRequestScope(Instant.now().plusSeconds(10));
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
      목록은 날짜와 고유키 오름차순, 기본 50 최대 250. nextCursor는 동일 필터로 전달. 종료 시 null.
      stockSeries PRICE는 vw_stock_daily_price_adj: adj_* 수정가격(원), raw_close 원종가(원), raw_volume 원거래량(주).
      METRIC 가격·수익률은 수정주가 기반. ret_*·dist_*는 비율(0.01=1%), tv_*·net_* 금액은 원.
      VALUATION 시총 원, EPS/BPS 원, PER/PBR 배, foreign_hold_rate %. ETF_NAV close_price 원가격(원), nav 원, disparity_rate %.
      INDEX·INDEX_METRIC 지수 포인트, ret_* 비율. BREADTH *_ratio는 비율. MASTER_HISTORY는 valid_from 기준 변경 이력, ADJUST_EVENT의 *_factor는 배수. INDEX 지수 포인트. GLOBAL 가격은 원천 통화/포인트이며 symbol·market_div로 구분. change_rate 필드는 %(1=1%).
      INVESTOR 금액은 원, null은 결측, 0은 실제 0입니다.
      종목 5일·업종 수급 합계는 복구된 비결측 값만 집계하므로 부분 복구 기간은 전체 합계로 해석하지 마세요.
      MARKET_INVESTOR 금액은 원(KIS 백만원 ×1e6), 수량은 천주(KIS 원천 그대로), null은 결측, 0은 실제 0입니다.
      stockFinancials는 available_from와 first_seen_at KST 날짜 모두 기준일 이하인 행만 제공. 재무 금액은 백만원, 비율은 %.
      재무·거시 정정 이력이 완전한 당시 버전을 보장하지는 않습니다. MACRO는 available_from<=to 및 obs_date<to로 게시 지연 제한.
      VIX 포인트, UST10Y/UST2Y %. EVENT는 완결된 UTC 관측일, 기본 LIVE, BACKFILL은 재구성 값으로 구분.
      SECTOR 파생 시계열은 현재 구성으로 과거를 근사합니다. stockSectors는 저장된 유효기간 기준이며 과거 완전 복원을 보장하지 않습니다.
      누락값은 null 또는 필드 없음이며 0으로 해석하지 마세요. 주가 수정계수는 현재 뷰 기준으로 과거 시점의 계수 버전을 보장하지 않습니다.
      원천: 국내 KIS/KRX, 글로벌 KIS, 거시 CBOE/TREASURY, 사건 GDELT. 상세 필드 의미·단위·코드는 stock://catalog 확인.
      """;
  static final String ADVICE_GUIDE = """
      listAdvices 기본 DAILY/LIVE, 최근 90일. 다른 종류로 대체하지 않습니다. 0픽은 관망, 빈 채점은 미채점입니다.
      adviceDetail: header 기준일·실제 dataAsOf, candidates 동결 정량 후보·signals, picks 근거·위험·인용,
      candidateScores/callScores 사후 채점, morningCheck/intradayChecks 점검, notes 오답노트.
      성과 가격은 수정주가, 수익률·excessRet은 비율(0.01=1%). 방향·stage·status를 함께 읽으세요.
      adviceScores 기본 호라이즌은 advisor.horizons의 해당 판단 종류. signalIc는 기존 IC 창 및 n_eff=IC일수/호라이즌 계산.
      adviceWeights는 가중치 세트·시그널 가중치 이력, adviceLessons는 근거·조건·상태, adviceNotes 가격은 원가격(원).
      성과·통계는 표본 수와 데이터 품질을 함께 확인하세요. H60/H180 판정 한계는 기존 설정을 따릅니다.
      수집 실행·새 판단 생성·삭제·설정 변경·토큰·채팅·프롬프트 원문은 제공하지 않습니다. 투자 자문이 아닌 개인 실험입니다.
      """;
}
