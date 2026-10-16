package kr.hvy.blog.modules.mcp;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Fixed SQL sources only. Cursor includes the query scope to prevent reuse with different filters. */
@Service
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpReadService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  public McpReadService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }
  public record Source(String table, String code, String date, String key) {}
  static final Map<String, Source> STOCK = Map.of(
      "MASTER_HISTORY", new Source("tb_stock_master_history", "ticker", "valid_from", "ticker"),
      "ADJUST_EVENT", new Source("tb_stock_adjust_event", "ticker", "effective_date", "action_type"),
      "RAW_PRICE", new Source("tb_stock_daily_price", "ticker", "trade_date", "ticker"),
      "PRICE", new Source("vw_stock_daily_price_adj", "ticker", "trade_date", "ticker"),
      "METRIC", new Source("tb_stock_daily_metric", "ticker", "trade_date", "ticker"),
      "VALUATION", new Source("tb_stock_valuation_daily", "ticker", "trade_date", "ticker"),
      "INVESTOR", new Source("tb_stock_investor_daily", "ticker", "trade_date", "ticker"),
      "MARKET_STAT", new Source("tb_stock_market_stat_daily", "ticker", "trade_date", "ticker"),
      "ETF_NAV", new Source("tb_stock_etf_nav_daily", "ticker", "trade_date", "ticker"));
  static final Map<String, Source> MARKET = Map.of(
      "INDEX_METRIC", new Source("mv_stock_index_metric", "index_code", "trade_date", "index_code"),
      "BREADTH", new Source("mv_stock_market_breadth_daily", "market_type", "trade_date", "market_type"),
      "INDEX", new Source("tb_stock_index_daily", "index_code", "trade_date", "index_code"),
      "MARKET_INVESTOR", new Source("tb_stock_market_investor_daily", "market_type", "trade_date", "market_type"),
      "SECTOR", new Source("mv_stock_sector_daily", "sector_code", "trade_date", "sector_code"),
      "GLOBAL", new Source("tb_stock_global_market_daily", "symbol", "trade_date", "symbol"),
      "MACRO", new Source("tb_stock_macro_daily", "series_code", "obs_date", "series_code"),
      "EVENT", new Source("tb_stock_event_timeline", "theme_code", "obs_date", "theme_code || '|' || source"));

  public Map<String, Object> series(boolean stock, String type, String code, String from, String to,
      String source, Integer limit, String cursor) {
    Source table = (stock ? STOCK : MARKET).get(type);
    if (table == null) throw new IllegalArgumentException("지원하지 않는 시계열 종류");
    if (code == null || code.isBlank()) throw new IllegalArgumentException("code/ticker 필수");
    String extra = "";
    MapSqlParameterSource params = new MapSqlParameterSource("code", code.trim());
    if ("EVENT".equals(type)) {
      String selected = source == null || source.isBlank() ? "LIVE" : source;
      if (!List.of("LIVE", "BACKFILL").contains(selected)) throw new IllegalArgumentException("source: LIVE 또는 BACKFILL");
      extra = " AND source = :source AND obs_date < (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date";
      params.addValue("source", selected);
    }
    if ("MACRO".equals(type)) extra = " AND available_from <= :end AND obs_date < :end";
    return page(table, table.code + " = :code" + extra, params, from, to, limit, cursor);
  }

  public Map<String, Object> master(String ticker) {
    return Map.of("items", rows("SELECT to_jsonb(t) AS data FROM (SELECT " + McpFields.columns("tb_stock_master") + " FROM tb_stock_master) t WHERE ticker = :ticker",
        new MapSqlParameterSource("ticker", ticker)));
  }

  public Map<String, Object> financials(String ticker, String asOf, Integer limit, String cursor) {
    return page(new Source("tb_stock_financial", "ticker", "available_from",
        "fiscal_period || '|' || period_type || '|' || lpad(revision_seq::text, 10, '0')"),
        "ticker = :ticker AND (first_seen_at AT TIME ZONE 'Asia/Seoul')::date <= :end",
        new MapSqlParameterSource("ticker", ticker), "0001-01-01", asOf, limit, cursor);
  }
  public Map<String, Object> actions(String ticker, String from, String to, Integer limit, String cursor) {
    return page(new Source("tb_stock_corporate_action", "ticker", "effective_date", "lpad(id::text, 20, '0')"),
        "ticker = :ticker", new MapSqlParameterSource("ticker", ticker), from, to, limit, cursor);
  }
  public Map<String, Object> sectors(String ticker, String asOf, Integer limit, String cursor) {
    return page(new Source("tb_stock_sector_map", "ticker", "valid_from", "sector_code"),
        "ticker = :ticker AND (valid_to IS NULL OR valid_to >= :end)", new MapSqlParameterSource("ticker", ticker),
        "0001-01-01", asOf, limit, cursor);
  }
  public Map<String, Object> advices(String from, String to, String kind, String variant, Integer limit, String cursor) {
    String k = kind == null || kind.isBlank() ? "DAILY" : kind;
    String v = variant == null || variant.isBlank() ? "LIVE" : variant;
    kr.hvy.blog.modules.advisor.domain.code.AdviceKind.valueOf(k);
    kr.hvy.blog.modules.advisor.domain.code.AdviceVariant.valueOf(v);
    return page(new Source("tb_advisor_advice", null, "base_date", "lpad(advice_id::text, 20, '0')"),
        "advice_kind = :kind AND variant = :variant", new MapSqlParameterSource("kind", k).addValue("variant", v),
        from, to, limit, cursor);
  }
  public Map<String, Object> lessons(String from, String to, Integer limit, String cursor) {
    return page(new Source("tb_advisor_lesson", null, "(created_at AT TIME ZONE 'Asia/Seoul')::date", "lpad(lesson_id::text, 20, '0')"),
        "true", new MapSqlParameterSource(), from, to, limit, cursor);
  }
  public Map<String, Object> notes(String from, String to, Integer limit, String cursor) {
    return page(new Source("tb_advisor_pick_note", null, "base_date", "lpad(note_id::text, 20, '0')"),
        "true", new MapSqlParameterSource(), from, to, limit, cursor);
  }
  public Map<String, Object> weights(String from, String to, int horizon, Integer limit, String cursor) {
    Map<String, Object> result = page(new Source("tb_advisor_weight_set", null, "as_of", "lpad(weight_set_id::text, 20, '0')"),
        "horizon_days = :horizon", new MapSqlParameterSource("horizon", horizon), from, to, limit, cursor);
    @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    for (Map<String, Object> item : items) {
      item.put("weights", rows("SELECT to_jsonb(t) AS data FROM (SELECT " + McpFields.columns("tb_advisor_signal_weight") + " FROM tb_advisor_signal_weight) t WHERE weight_set_id = :id ORDER BY signal_code",
          new MapSqlParameterSource("id", item.get("weight_set_id"))));
    }
    return result;
  }

  private Map<String, Object> page(Source source, String filter, MapSqlParameterSource params, String from,
      String to, Integer limit, String cursor) {
    LocalDate end = end(to);
    LocalDate start = from == null || from.isBlank() ? end.minusDays(90) : LocalDate.parse(from);
    if (start.isAfter(end)) throw new IllegalArgumentException("from 은 to 이전이어야 합니다");
    if (limit != null && limit < 1) throw new IllegalArgumentException("limit must be positive");
    int size = limit == null ? 50 : Math.min(250, limit);
    params.addValue("start", start).addValue("end", end);
    String scope = source.table + filter + params.getValues();
    String after = "";
    if (cursor != null && !cursor.isBlank()) {
      try {
        List<String> decoded = mapper.readValue(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), new TypeReference<List<String>>() {});
        if (decoded.size() != 3 || !scope.equals(decoded.get(0))) throw new IllegalArgumentException();
        LocalDate.parse(decoded.get(1));
        if (decoded.get(2) == null) throw new IllegalArgumentException();
        params.addValue("cursorDate", decoded.get(1)).addValue("cursorKey", decoded.get(2));
        after = " AND ((" + source.date + ")::text, (" + source.key + ")::text) > (:cursorDate, :cursorKey)";
      } catch (RuntimeException e) { throw new IllegalArgumentException("유효하지 않은 cursor"); }
    }
    params.addValue("limit", size + 1);
    String sql = "SELECT to_jsonb(t) AS data, (" + source.date + ")::text AS cursor_date, ("
        + source.key + ")::text AS cursor_key FROM (SELECT " + McpFields.columns(source.table) + " FROM " + source.table + ") t WHERE " + filter
        + " AND " + source.date + " BETWEEN :start AND :end" + after
        + " ORDER BY (" + source.date + ")::text, (" + source.key + ")::text LIMIT :limit";
    List<Map<String, Object>> items = new ArrayList<>();
    List<String> cursors = new ArrayList<>();
    jdbc.query(sql, params, rs -> {
      items.add(mapper.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {}));
      cursors.add(Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsString(
          List.of(scope, rs.getString("cursor_date"), rs.getString("cursor_key"))).getBytes(StandardCharsets.UTF_8)));
    });
    boolean more = items.size() > size;
    if (more) items.removeLast();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requestedAsOf", to == null || to.isBlank() ? end.toString() : to);
    result.put("queryTo", end.toString());
    result.put("from", start.toString());
    result.put("dataAsOf", items.isEmpty() ? null : mapper.readValue(new String(Base64.getUrlDecoder().decode(cursors.get(items.size()-1)), StandardCharsets.UTF_8), new TypeReference<List<String>>() {}).get(1));
    result.put("items", items);
    result.put("nextCursor", more ? cursors.get(size - 1) : null);
    return result;
  }
  List<Map<String, Object>> rows(String sql, MapSqlParameterSource params) {
    return jdbc.query(sql, params, (rs, i) -> mapper.readValue(rs.getString("data"), new TypeReference<Map<String, Object>>() {}));
  }
  public Map<String, Object> catalog() {
    Map<String, Object> catalog = new LinkedHashMap<>();
    for (var entry : MARKET.entrySet()) {
      Source source = entry.getValue();
      catalog.put(entry.getKey(), rows("SELECT jsonb_build_object('code', " + source.code + ") AS data FROM "
          + source.table + " GROUP BY " + source.code + " ORDER BY " + source.code, new MapSqlParameterSource()));
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (String table : McpFields.COLUMNS.keySet()) {
      Source source = new Source(table, null, null, null);
      Map<String, String> aliases = McpFields.aliases(table);
      List<Map<String, Object>> columns = rows("SELECT jsonb_build_object('field', a.attname, 'type', format_type(a.atttypid, a.atttypmod), "
          + "'description', col_description(a.attrelid, a.attnum)) AS data FROM pg_attribute a WHERE a.attrelid = to_regclass(:table) "
          + "AND a.attnum > 0 AND NOT a.attisdropped AND a.attname IN (:fields) ORDER BY a.attnum",
          new MapSqlParameterSource("table", source.table).addValue("fields", McpFields.names(source.table)));
      // 응답 키가 별칭(*_pct)인 컬럼은 카탈로그 필드명도 별칭으로 — 설명은 DB 주석 그대로
      for (Map<String, Object> column : columns) column.put("field", aliases.getOrDefault(String.valueOf(column.get("field")), String.valueOf(column.get("field"))));
      fields.put(source.table, columns);
    }
    catalog.put("fields", fields);
    catalog.put("adviceKinds", java.util.Arrays.stream(kr.hvy.blog.modules.advisor.domain.code.AdviceKind.values()).map(Enum::name).toList());
    catalog.put("adviceVariants", java.util.Arrays.stream(kr.hvy.blog.modules.advisor.domain.code.AdviceVariant.values()).map(Enum::name).toList());
    catalog.put("units", McpConfig.STOCK_GUIDE);
    catalog.put("adviceGuide", McpConfig.ADVICE_GUIDE);
    catalog.put("stockCodes", "resolveStock으로 검색하세요");
    return catalog;
  }
  /**
   * 데이터별 최신 기준일. 소스 19개를 스칼라 서브쿼리로 묶은 **단일 문** 1회 + advice 집계 1회 — 2026-10-16 이전엔 소스마다 statement 를 순차 실행해
   * (MV·global·financial 은 날짜 인덱스도 없어 전체 스캔) 합이 트랜잭션 10s 를 넘겨 항상 timeout 이었다(plan/mcp.md P1-7). 각 서브쿼리는
   * ORDER BY date DESC LIMIT 1 로 날짜 인덱스(migrate 20261016_02)를 역방향 스캔한다. NEWS 는 식 인덱스가 없으므로 published_at 범위 비교로 바꿨다.
   */
  public Map<String, Object> freshness() {
    LocalDate today = MarketClock.today();
    var sources = new LinkedHashMap<String, Source>();
    sources.putAll(STOCK);
    sources.putAll(MARKET);
    sources.put("FINANCIAL", new Source("tb_stock_financial", null, "available_from", null));
    sources.put("NEWS", new Source("tb_stock_news", null, "published_at", null));
    Map<String, Object> dates = new LinkedHashMap<>();
    StringBuilder sql = new StringBuilder();
    for (var entry : sources.entrySet()) {
      dates.put(entry.getKey(), null);
      if (!sql.isEmpty()) sql.append(" UNION ALL ");
      sql.append("SELECT '").append(entry.getKey()).append("' AS k, ").append(latestDateSql(entry.getKey(), entry.getValue())).append(" AS d");
    }
    jdbc.query(sql.toString(), new MapSqlParameterSource("today", today).addValue("tomorrow", today.plusDays(1)),
        rs -> { dates.put(rs.getString("k"), rs.getString("d")); });
    var advice = rows("SELECT jsonb_build_object('kind', advice_kind, 'baseDate', max(base_date)) AS data "
        + "FROM tb_advisor_advice WHERE variant = 'LIVE' AND base_date <= :today GROUP BY advice_kind ORDER BY advice_kind",
        new MapSqlParameterSource("today", MarketClock.today()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("today", MarketClock.today().toString());
    result.put("dataAsOf", dates);
    result.put("latestAdviceByKind", advice);
    return result;
  }

  /** 소스 1개의 최신 날짜 스칼라 서브쿼리(::text). NEWS 는 KST 익일 자정 미만의 마지막 published_at 을 KST 날짜로 */
  private static String latestDateSql(String key, Source source) {
    if ("NEWS".equals(key)) {
      return "(SELECT (published_at AT TIME ZONE 'Asia/Seoul')::date FROM tb_stock_news "
          + "WHERE published_at < (CAST(:tomorrow AS date)::timestamp AT TIME ZONE 'Asia/Seoul') ORDER BY published_at DESC LIMIT 1)::text";
    }
    return "(SELECT " + source.date + " FROM " + source.table + " WHERE " + source.date + " <= :today ORDER BY " + source.date + " DESC LIMIT 1)::text";
  }

  static LocalDate end(String value) {
    LocalDate date = value == null || value.isBlank() ? MarketClock.today() : LocalDate.parse(value);
    return date.isAfter(MarketClock.today()) ? MarketClock.today() : date;
  }
}
