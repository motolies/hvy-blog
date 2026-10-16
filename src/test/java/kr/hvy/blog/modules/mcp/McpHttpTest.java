package kr.hvy.blog.modules.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdvisorChatProperties;
import kr.hvy.blog.modules.advisor.application.chat.tool.*;
import kr.hvy.blog.modules.advisor.application.service.*;
import kr.hvy.blog.modules.advisor.repository.jdbc.*;
import kr.hvy.blog.modules.stock.repository.jdbc.*;
import kr.hvy.blog.modules.advisor.application.service.TradingCalendar;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class McpHttpTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
  SpringApplicationBuilder application(boolean enabled, boolean advisor, String key) {
    return new SpringApplicationBuilder(App.class).profiles("mcp-http-fixture").properties(Map.ofEntries(
        Map.entry("spring.config.location", "optional:classpath:/mcp-empty.yml"),
        Map.entry("server.port", "0"), Map.entry("mcp.enabled",enabled), Map.entry("advisor.enabled",advisor),
        Map.entry("MCP_API_KEY",key), Map.entry("spring.ai.mcp.server.enabled",enabled),
        Map.entry("spring.ai.mcp.server.protocol","STATELESS"), Map.entry("spring.ai.mcp.server.type","SYNC"),
        Map.entry("spring.ai.mcp.server.tool-callback-converter",false),
        Map.entry("spring.ai.mcp.server.annotation-scanner.enabled",false),
        Map.entry("spring.ai.mcp.server.request-timeout","10s"),
        Map.entry("spring.ai.mcp.server.capabilities.prompt",false), Map.entry("spring.ai.mcp.server.capabilities.completion",false)));
  }
  @Test void protocolAuthenticationAndReadTools() throws Exception {
    try (var c = DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword())) {
      for (String file : java.util.List.of("stock-schema.sql","stock-derived.sql","advisor-schema.sql")) ScriptUtils.executeSqlScript(c,new ClassPathResource("db/"+file));
    }
    try (var context = application(true,false,"test-mcp-key").run()) {
      int port = ((ServletWebServerApplicationContext)context).getWebServer().getPort();
      String base = "http://localhost:"+port;
      assertThat(post(base,null,null)).isEqualTo(401);
      assertThat(post(base,"wrong",null)).isEqualTo(401);
      assertThat(post(base,"test-mcp-key","https://evil.invalid")).isEqualTo(403);
      assertThat(post(base,"test-mcp-key","https://hvy.kr")).isEqualTo(200);
      var options = HttpRequest.newBuilder(URI.create(base + "/mcp"))
          .header("Origin", "https://hvy.kr").header("Access-Control-Request-Method", "POST")
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
      assertThat(HttpClient.newHttpClient().send(options, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
      var jdbc = context.getBean(JdbcTemplate.class);
      jdbc.execute("INSERT INTO tb_stock_master(ticker,stock_name,market_type,security_group,created_at,updated_at) VALUES ('005930','삼성전자','KOSPI','ST',now(),now())");
      jdbc.execute("INSERT INTO tb_stock_index_daily(index_code,trade_date,open_price,high_price,low_price,close_price) VALUES ('0001','2026-01-02',100,100,100,100)");
      jdbc.execute("INSERT INTO tb_advisor_weight_set(as_of,source,is_active,horizon_days) VALUES ('2020-01-01','SEED',true,5)");
      long runId = jdbc.queryForObject("INSERT INTO tb_advisor_run(job_type,trigger_type,status,created_at,updated_at) VALUES ('ADVISE','API','SUCCEEDED',now(),now()) RETURNING run_id", Long.class);
      long adviceId = jdbc.queryForObject("INSERT INTO tb_advisor_advice(run_id,base_date,advice_kind,variant) VALUES (?,'2026-01-02','DAILY','LIVE') RETURNING advice_id",Long.class,runId);
      var transport = HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp")
          .requestBuilder(HttpRequest.newBuilder().header("Authorization","Bearer test-mcp-key"))
          .openConnectionOnStartup(false).build();
      try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
        assertThat(client.initialize().serverInfo().name()).isNotBlank();
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(McpConfig.READ_TOOLS);
        for (var tool : client.listTools().tools()) {
          assertThat(tool.annotations().readOnlyHint()).isTrue();
          assertThat(tool.annotations().destructiveHint()).isFalse();
          assertThat(tool.annotations().idempotentHint()).isTrue();
          assertThat(tool.annotations().openWorldHint()).isFalse();
        }
        for (String tool : java.util.List.of("dataFreshness", "advicePerformance", "morningPerformanceComparison", "activeAdviceWeights"))
          assertThat(client.callTool(new McpSchema.CallToolRequest(tool, Map.of())).isError()).as(tool).isFalse();
        assertThat(client.callTool(new McpSchema.CallToolRequest("activeAdviceWeights", Map.of())).structuredContent().toString()).contains("2020-01-01");
        assertThat(client.callTool(new McpSchema.CallToolRequest("dataFreshness", Map.of())).structuredContent().toString()).contains("DAILY", "2026-01-02");
        for (String topic : java.util.List.of("stock", "advice", "catalog"))
          assertThat(client.callTool(new McpSchema.CallToolRequest("dataGuide", Map.of("topic", topic))).isError()).isFalse();
        for (String tool : java.util.List.of("stockSnapshot", "stockSeries")) {
          var bad = client.callTool(new McpSchema.CallToolRequest(tool, tool.equals("stockSnapshot") ? Map.of("ticker", "005930", "asOf", "2026-99-01") : Map.of("ticker", "005930", "type", "PRICE", "to", "2026-99-01")));
          assertThat(bad.isError()).isTrue();
          assertThat(bad.structuredContent().toString()).contains("bad_argument");
        }
        assertThat(client.callTool(new McpSchema.CallToolRequest("adviceScores", Map.of("adviceId", adviceId, "horizon", 999))).structuredContent().toString()).contains("bad_argument");
        assertThat(client.callTool(new McpSchema.CallToolRequest("adviceDetail", Map.of("adviceId", 999))).structuredContent().toString()).contains("not_found");
        assertThat(client.callTool(new McpSchema.CallToolRequest("advicePerformance", Map.of("kind", "H180"))).isError()).isFalse();
        var resolved = client.callTool(new McpSchema.CallToolRequest("resolveStock", Map.of("query","005930")));
        assertThat(resolved.isError()).isFalse();
        assertThat(resolved.structuredContent()).isNotNull();
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readValue(
            ((McpSchema.TextContent) resolved.content().getFirst()).text(), Map.class)).isEqualTo(resolved.structuredContent());
        assertThat(((McpSchema.TextContent) resolved.content().getFirst()).text()).contains("005930").doesNotContain("\"status\":\"SUCCESS\"");
        assertThat(client.callTool(new McpSchema.CallToolRequest("marketSeries",Map.of("type","INDEX","code","0001","from","2026-01-01","to","2026-01-03"))).isError()).isFalse();
        assertThat(client.callTool(new McpSchema.CallToolRequest("listAdvices",Map.of())).isError()).isFalse();
        var detail = client.callTool(new McpSchema.CallToolRequest("adviceDetail",Map.of("adviceId",adviceId)));
        assertThat(detail.isError()).isFalse();
        assertThat(((McpSchema.TextContent)detail.content().getFirst()).text()).contains("\"picks\":[]").doesNotContain("systemFingerprint", "memoryJson", "raw_json");
        assertThat(client.callTool(new McpSchema.CallToolRequest("adviceDetail",Map.of("adviceId",999))).isError()).isTrue();
        assertThat(client.callTool(new McpSchema.CallToolRequest("stockSeries",Map.of("ticker","005930","type","PRICE"))).isError()).isFalse();
        // screeningTop 저장 경로: 그날 DAILY LIVE 가 활성 세트로 저장한 후보가 있으면 mock 스크리닝(null 반환)을 타지 않고 STORED 로 답한다 (2026-10-16 P1-7)
        jdbc.execute("INSERT INTO tb_stock_daily_metric(ticker,trade_date) VALUES ('005930','2026-01-02')");
        jdbc.update("UPDATE tb_advisor_advice SET weight_set_id = (SELECT weight_set_id FROM tb_advisor_weight_set WHERE is_active LIMIT 1) WHERE advice_id = ?", adviceId);
        jdbc.update("INSERT INTO tb_advisor_candidate(advice_id,ticker,quant_rank,quant_score,stock_name,market_type,bench_index_code,signal_json) VALUES (?,'005930',1,0.7,'삼성전자','KOSPI','0001','{}'::jsonb)", adviceId);
        var screened = client.callTool(new McpSchema.CallToolRequest("screeningTop", Map.of("baseDate", "2026-01-02", "limit", 5)));
        assertThat(screened.isError()).isFalse();
        assertThat(screened.structuredContent().toString()).contains("STORED").contains("005930");
        // screenStocks(2026-10-16, 도구 35종): 스키마에 filters 배열 항목 속성이 있고, 허용 밖 컬럼·연산자는 bad_argument, 정상 호출은 오류 없음(유니버스 비어 0건)
        var screenTool = client.listTools().tools().stream().filter(t -> t.name().equals("screenStocks")).findFirst().orElseThrow();
        assertThat(screenTool.inputSchema().toString()).contains("filters").contains("column").contains("op").contains("value").contains("sort");
        var screen = client.callTool(new McpSchema.CallToolRequest("screenStocks", Map.of("filters", java.util.List.of(Map.of("column", "tv_ratio_5_60", "op", "GTE", "value", 3)),
            "sort", "ret_20d", "asOf", "2026-01-02")));
        assertThat(screen.isError()).as(screen.structuredContent().toString()).isFalse();
        assertThat(screen.structuredContent().toString()).contains("matched=0").contains("tv_ratio_5_60");
        assertThat(client.callTool(new McpSchema.CallToolRequest("screenStocks", Map.of("filters", java.util.List.of(Map.of("column", "secret_col", "op", "GTE", "value", 1)), "sort", "ret_20d")))
            .structuredContent().toString()).contains("bad_argument");
        assertThat(client.callTool(new McpSchema.CallToolRequest("screenStocks", Map.of("filters", java.util.List.of(Map.of("column", "ret_20d", "op", "LIKE", "value", 1)), "sort", "ret_20d")))
            .structuredContent().toString()).contains("bad_argument");
        assertThat(client.listResources().resources()).hasSize(3);
        for (String uri : java.util.List.of("stock://guide","advice://guide","stock://catalog"))
          assertThat(client.readResource(new McpSchema.ReadResourceRequest(uri)).contents()).isNotEmpty();
      }
    }
  }
  @Test void missingKeyFailsStartupAndDisabledStarts() {
    assertThatThrownBy(() -> { try (var ignored = application(true,false,"").run()) {} }).hasStackTraceContaining("MCP_ENABLED requires MCP_API_KEY");
    try (var context = application(false,false,"").run()) { assertThat(context.getBeansOfType(McpConfig.class)).isEmpty(); assertThat(context.getBeansOfType(AdviceToolkit.class)).isEmpty(); }
    try (var context = application(true,true,"test-key").run()) { assertThat(context.getBeansOfType(McpConfig.class)).hasSize(1); }
  }
  int post(String base,String key,String origin) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base+"/mcp")).header("Content-Type","application/json")
        .header("Accept","application/json, text/event-stream");
    if (key!=null) builder.header("Authorization","Bearer "+key);
    if (origin!=null) builder.header("Origin",origin);
    return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("mcp-http-fixture")
  @Import({McpConfig.class,McpSecurityConfig.class,McpDataToolkit.class,McpReadService.class,
      ToolSupport.class,StockToolkit.class,MarketToolkit.class,CalendarToolkit.class,AdviceToolkit.class})
  @ImportAutoConfiguration({
      org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
      org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
      org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration.class,
      org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
      org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration.class,
      org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration.class,
      org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration.class})
  static class App {
    @Bean DriverManagerDataSource ds() { return new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()); }
    @Bean JdbcTemplate jdbc(DriverManagerDataSource ds) { return new JdbcTemplate(ds); }
    @Bean NamedParameterJdbcTemplate named(DriverManagerDataSource ds) { return new NamedParameterJdbcTemplate(ds); }
    @Bean PlatformTransactionManager tx(DriverManagerDataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean AdvisorProperties properties() { return new AdvisorProperties(new MockEnvironment()); }
    @Bean AdvisorChatProperties chat(AdvisorProperties p) { return new AdvisorChatProperties(new MockEnvironment(),p); }
    @Bean StockLookupReader lookup(JdbcTemplate j) { return new StockLookupReader(j); }
    @Bean DerivedViewRefresher prices(JdbcTemplate j) { return new DerivedViewRefresher(j); }
    @Bean StockNewsWriter news() { return mock(StockNewsWriter.class); }
    @Bean MarketFeatureService features() { return mock(MarketFeatureService.class); }
    @Bean MarketTrendService trends() { return mock(MarketTrendService.class); }
    @Bean GlobalLinkService links() { return mock(GlobalLinkService.class); }
    @Bean MarketRegimeService regimes() { return mock(MarketRegimeService.class); }
    @Bean CandidateScreeningService screening() { return mock(CandidateScreeningService.class); }
    @Bean AdvisorKpiService kpi(NamedParameterJdbcTemplate j, AdvisorProperties p) { return new AdvisorKpiService(j, p); }
    @Bean TradingCalendar calendar() { return mock(TradingCalendar.class); }
    @Bean WeightSetRepository weights(JdbcTemplate j) { return new WeightSetRepository(j); }
    @Bean AdviceWriter advices(JdbcTemplate j) { return new AdviceWriter(j); }
    @Bean ScoreWriter scores(JdbcTemplate j) { return new ScoreWriter(new kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport(j),j); }
    @Bean MorningCheckWriter morning(JdbcTemplate j) { return new MorningCheckWriter(j); }
    @Bean IntradayCheckWriter intraday(JdbcTemplate j) { return new IntradayCheckWriter(j); }
    @Bean PickNoteRepository notes(JdbcTemplate j) { return new PickNoteRepository(j); }
    @Bean SignalIcWriter ic(JdbcTemplate j) { return new SignalIcWriter(new kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport(j),j); }
  }
}
