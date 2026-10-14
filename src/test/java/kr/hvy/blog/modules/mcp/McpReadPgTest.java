package kr.hvy.blog.modules.mcp;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class McpReadPgTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
  JdbcTemplate jdbc;
  McpReadService reader;
  TransactionTemplate tx;

  @BeforeAll static void schema() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      for (String file : List.of("stock-schema.sql", "stock-derived.sql", "advisor-schema.sql"))
        ScriptUtils.executeSqlScript(c, new ClassPathResource("db/" + file));
    }
  }
  @BeforeEach void setup() {
    var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    jdbc = new JdbcTemplate(ds);
    reader = new McpReadService(new NamedParameterJdbcTemplate(ds), JsonMapper.builder().build());
    tx = McpConfig.readTransaction(new DataSourceTransactionManager(ds));
    jdbc.execute("TRUNCATE tb_stock_investor_daily, tb_stock_daily_price, tb_stock_adjust_event, tb_stock_financial, tb_stock_macro_daily, tb_stock_event_timeline, tb_advisor_advice CASCADE");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_adjust_factor");
  }
  @SuppressWarnings("unchecked") List<Map<String, Object>> items(Map<String, Object> result) { return (List<Map<String, Object>>) result.get("items"); }

  @Test void investorPublishesAmountsAndFreshnessWithoutVersion() {
    jdbc.execute("INSERT INTO tb_stock_investor_daily(ticker,trade_date,foreign_net_amt) VALUES "
        + "('005930','2026-01-01',-1387781000000),('005930','2026-01-02',0)");
    var result = reader.series(true,"INVESTOR","005930","2026-01-01","2026-01-02",null,50,null);
    assertThat(items(result)).hasSize(2);
    assertThat(items(result).getFirst().get("foreign_net_amt")).isEqualTo(-1_387_781_000_000L);
    assertThat(items(result).getFirst().get("institution_net_amt")).isNull();
    assertThat(items(result).getLast().get("foreign_net_amt")).isEqualTo(0);
    assertThat(items(result).getFirst()).doesNotContainKey("normalization_version");
    assertThat(result.get("dataAsOf")).isEqualTo("2026-01-02");
    assertThat(((Map<?, ?>) reader.freshness().get("dataAsOf")).get("INVESTOR")).isEqualTo("2026-01-02");
  }

  @Test void adjustedPriceAndContinuousPages() {
    for (int day = 1; day <= 4; day++) jdbc.update("INSERT INTO tb_stock_daily_price(ticker,trade_date,open_price,high_price,low_price,close_price,volume,trading_value) VALUES ('005930',?::date,100,100,100,100,10,1000)", "2026-01-0" + day);
    jdbc.execute("INSERT INTO tb_stock_adjust_event VALUES ('005930','2026-01-03','SPLIT',0.5,2,true,now())");
    jdbc.execute("REFRESH MATERIALIZED VIEW mv_stock_adjust_factor");
    var first = reader.series(true,"PRICE","005930","2026-01-01","2026-01-04",null,2,null);
    assertThat(items(first)).hasSize(2);
    assertThat(((Number) items(first).getFirst().get("adj_close")).doubleValue()).isEqualTo(50);
    assertThat(((Number) items(first).getFirst().get("raw_close")).doubleValue()).isEqualTo(100);
    var second = reader.series(true,"PRICE","005930","2026-01-01","2026-01-04",null,2,(String) first.get("nextCursor"));
    assertThat(items(second)).extracting(x -> x.get("trade_date")).containsExactly("2026-01-03","2026-01-04");
    assertThat(second.get("nextCursor")).isNull();
    assertThatThrownBy(() -> reader.series(true,"PRICE","000001","2026-01-01","2026-01-04",null,2,(String) first.get("nextCursor"))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void financialBitemporalCutoffAndCompositeKeys() {
    jdbc.execute("INSERT INTO tb_stock_financial(ticker,fiscal_period,period_type,revision_seq,available_from,available_rule,first_seen_at,revenue) VALUES "
        + "('005930','202512','Y',0,'2026-01-01','TEST','2026-01-02T00:00:00+09:00',null),"
        + "('005930','202512','Y',1,'2026-01-01','TEST','2026-01-04T00:00:00+09:00',999),"
        + "('005930','202509','Q',0,'2026-01-01','TEST','2026-01-01T00:00:00+09:00',100),"
        + "('005930','202603','Q',0,'2026-04-01','TEST','2026-01-01T00:00:00+09:00',100)");
    var first = reader.financials("005930","2026-01-03",1,null);
    var second = reader.financials("005930","2026-01-03",1,(String)first.get("nextCursor"));
    assertThat(items(first)).hasSize(1);
    assertThat(items(second)).hasSize(1);
    assertThat(items(second).getFirst().get("revenue")).isNull();
    assertThat(second.get("nextCursor")).isNull();
  }
  @Test void macroPublicationDelayAndLiveBackfill() {
    jdbc.execute("INSERT INTO tb_stock_macro_daily(series_code,obs_date,value,source,available_from) VALUES ('VIX','2026-01-01',20,'CBOE','2026-01-02'),('VIX','2026-01-02',30,'CBOE','2026-01-03')");
    assertThat(items(reader.series(false,"MACRO","VIX","2026-01-01","2026-01-02",null,50,null))).hasSize(1);
    jdbc.execute("INSERT INTO tb_stock_event_timeline(theme_code,obs_date,source,article_vol) VALUES ('WAR','2026-01-01','LIVE',10),('WAR','2026-01-01','BACKFILL',20)");
    assertThat(items(reader.series(false,"EVENT","WAR","2026-01-01","2026-01-02",null,50,null)).getFirst().get("article_vol")).isEqualTo(10);
    assertThat(items(reader.series(false,"EVENT","WAR","2026-01-01","2026-01-02","BACKFILL",50,null)).getFirst().get("article_vol")).isEqualTo(20);
    assertThat(items(reader.advices("2026-01-01","2026-01-02",null,null,50,null))).isEmpty();
  }
  @Test void sqlDeadlineAndReadOnlyEnforced() {
    assertThatThrownBy(() -> tx.execute(s -> {
      jdbc.execute("SET LOCAL statement_timeout = 50");
      jdbc.execute("SELECT pg_sleep(0.2)"); return null;
    })).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    assertThatThrownBy(() -> tx.execute(s -> {
      jdbc.execute("SET TRANSACTION READ ONLY");
      jdbc.update("INSERT INTO tb_stock_adjust_event VALUES ('WRITE','2026-01-01','SPLIT',1,1,true,now())");
      return null;
    })).hasRootCauseMessage("ERROR: cannot execute INSERT in a read-only transaction");
    // Tool boundary config uses the requested production 5 second statement timeout.
    tx.execute(s -> { jdbc.execute("SET LOCAL statement_timeout = 5000");
      assertThat(jdbc.queryForObject("SHOW statement_timeout",String.class)).isEqualTo("5s"); return null; });
  }
  @Test void explicitProjectionDoesNotPublishNewColumns() {
    jdbc.execute("ALTER TABLE tb_stock_master ADD COLUMN IF NOT EXISTS secret_future_column text");
    jdbc.execute("INSERT INTO tb_stock_master(ticker,stock_name,market_type,security_group,created_at,updated_at,secret_future_column) "
        + "VALUES ('SAFE','Safe','KOSPI','ST',now(),now(),'must-not-leak') ON CONFLICT (ticker) DO NOTHING");
    assertThat(reader.master("SAFE").toString()).doesNotContain("secret_future_column", "must-not-leak", "created_by");
    assertThat(reader.catalog().toString()).doesNotContain("secret_future_column");
  }

  @Test void allSourcesAndFreshnessWorkWithoutMetrics() {
    for (String type : McpReadService.STOCK.keySet())
      assertThat(reader.series(true,type,"005930",null,null,null,null,null)).containsKey("items");
    for (String type : McpReadService.MARKET.keySet())
      assertThat(reader.series(false,type,"0001",null,null,null,null,null)).containsKey("items");
    assertThat(reader.freshness()).containsKeys("dataAsOf", "latestAdviceByKind");
    assertThatThrownBy(() -> reader.series(true,"PRICE","005930","invalid",null,null,null,null)).isInstanceOf(java.time.format.DateTimeParseException.class);
    assertThatThrownBy(() -> reader.series(true,"PRICE","005930",null,null,null,null,"bad-cursor")).isInstanceOf(IllegalArgumentException.class);
    var future = reader.series(true,"PRICE","005930",null,"2999-01-01",null,null,null);
    assertThat(future.get("requestedAsOf")).isEqualTo("2999-01-01");
    assertThat(future.get("queryTo")).isEqualTo(kr.hvy.blog.modules.stock.domain.model.MarketClock.today().toString());
  }

  @Test void toolBoundaryClassifiesTimeoutAndPreventsWrites() {
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
        .name("testRead").description("Read test").inputSchema("{\"type\":\"object\"}").build());
    org.mockito.Mockito.when(callback.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          jdbc.execute("SET LOCAL statement_timeout = 20");
          jdbc.execute("SELECT pg_sleep(0.1)");
          return "{}";
        });
    var timeout = McpConfig.call(callback, Map.of(), JsonMapper.builder().build(), tx, jdbc);
    assertThat(timeout.isError()).isTrue();
    assertThat(timeout.structuredContent().toString()).contains("timeout");
    org.mockito.Mockito.when(callback.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          jdbc.execute("INSERT INTO tb_stock_adjust_event VALUES ('WRITE','2026-01-01','SPLIT',1,1,true,now())");
          return "{}";
        });
    var write = McpConfig.call(callback, Map.of(), JsonMapper.builder().build(), tx, jdbc);
    assertThat(write.isError()).isTrue();
    assertThat(write.structuredContent().toString()).contains("internal").doesNotContain("INSERT", "tb_stock");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_stock_adjust_event WHERE ticker='WRITE'", Integer.class)).isZero();
  }

  @Test void catalogUsesOnlyDataTables() {
    var catalog = reader.catalog();
    assertThat(catalog).containsKeys("INDEX","SECTOR","EVENT","fields");
    assertThat(catalog.toString()).doesNotContain("access_token","tb_advisor_chat","raw_json");
  }
}
