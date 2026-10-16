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
import org.springframework.core.env.Environment;
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
  private final Environment env;
  public McpReadService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, Environment env) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.env = env;
  }

  /** 수집·입력 스위치 1개: 설정 키, 꺼졌을 때 날짜가 멈추는 dataFreshness 키, 의미. defaultValue 는 키가 없을 때의 값(@ConditionalOnProperty 와 같게) */
  record Switch(String flag, boolean defaultValue, List<String> sources, String effect) {
  }

  /**
   * dataFreshness 가 함께 보여 주는 설정 스위치(2026-10-16, MCP 2차 점검). MACRO 9/18·NEWS 9/11·EVENT null 이 장애로 읽혔지만 실제로는 수집이 설정으로 꺼져 있었다.
   */
  static final List<Switch> FRESHNESS_SWITCHES = List.of(
      new Switch("scheduler.stock-macro.enabled", false, List.of("MACRO"), "거시 지표 수집 꺼짐 — 날짜가 멈춘 것은 장애가 아니다"),
      new Switch("scheduler.stock-eventfeed.enabled", false, List.of("EVENT", "NEWS"), "사건 피드(GDELT) 수집 꺼짐 — 날짜가 멈춘 것은 장애가 아니다"),
      new Switch("advisor.news.enabled", false, List.of("NEWS"), "판단 입력에 뉴스를 쓰지 않는다"),
      new Switch("kis.stats.enabled", true, List.of("MARKET_STAT"), "DAILY 시장 통계(공매도·신용·프로그램) 단계 꺼짐"));
  /** 조회 소스. projection 은 McpFields.COLUMNS 의 키(기본 table, 요약 투영은 "table:summary") */
  public record Source(String table, String code, String date, String key, String projection) {
    public Source(String table, String code, String date, String key) {
      this(table, code, date, key, table);
    }
  }
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
    Map<String, Object> result = page(table, table.code + " = :code" + extra, params, from, to, limit, cursor);
    // 2026-10-16(P2): 지수·업종 시계열은 코드만 있어 업종명을 알 수 없었다 — 코드 1개 호출이라 행마다가 아니라 최상위 codeName 으로
    if (!stock && List.of("INDEX", "INDEX_METRIC", "SECTOR").contains(type)) {
      String name = codeName(code.trim());
      if (name != null) result.put("codeName", name);
    }
    return result;
  }

  /** 지수·업종 코드의 이름 — 업종 코드 마스터(idxcode) 우선, 없으면 KRX 섹터 매핑의 최신 이름. 없으면 null */
  String codeName(String code) {
    List<String> names = jdbc.query("SELECT name FROM (SELECT 1 AS pri, index_name AS name FROM tb_stock_index_master WHERE index_code = :code "
            + "UNION ALL SELECT 2, sector_name FROM (SELECT sector_name FROM tb_stock_sector_map WHERE sector_code = :code AND source = 'KRX' "
            + "ORDER BY valid_from DESC LIMIT 1) s) x ORDER BY pri LIMIT 1",
        new MapSqlParameterSource("code", code), (rs, i) -> rs.getString("name"));
    return names.isEmpty() ? null : names.get(0);
  }

  public Map<String, Object> master(String ticker) {
    return Map.of("items", rows("SELECT to_jsonb(t) AS data FROM (SELECT " + McpFields.columns("tb_stock_master") + " FROM tb_stock_master) t WHERE ticker = :ticker",
        new MapSqlParameterSource("ticker", ticker)));
  }

  /** 재무 이력 — 최신 available_from 순(내림차순, 2026-10-16). 그 전엔 2004년 결산부터 와서 limit 50 안에 최신이 없었다(P2) */
  public Map<String, Object> financials(String ticker, String asOf, Integer limit, String cursor) {
    return page(new Source("tb_stock_financial", "ticker", "available_from",
        "fiscal_period || '|' || period_type || '|' || lpad(revision_seq::text, 10, '0')"),
        "ticker = :ticker AND (first_seen_at AT TIME ZONE 'Asia/Seoul')::date <= :end",
        new MapSqlParameterSource("ticker", ticker), "0001-01-01", asOf, limit, cursor, true);
  }
  /** 기업행사 — from 생략 시 전체 이력, 최신 effective_date 순(2026-10-16). 종목당 수십 건이라 전체여도 작고 기본 90일로는 4월 무상증자가 안 보였다(P2) */
  public Map<String, Object> actions(String ticker, String from, String to, Integer limit, String cursor) {
    return page(new Source("tb_stock_corporate_action", "ticker", "effective_date", "lpad(id::text, 20, '0')"),
        "ticker = :ticker", new MapSqlParameterSource("ticker", ticker), from == null || from.isBlank() ? "0001-01-01" : from, to, limit, cursor, true);
  }
  public Map<String, Object> sectors(String ticker, String asOf, Integer limit, String cursor) {
    return page(new Source("tb_stock_sector_map", "ticker", "valid_from", "sector_code"),
        "ticker = :ticker AND (valid_to IS NULL OR valid_to >= :end)", new MapSqlParameterSource("ticker", ticker),
        "0001-01-01", asOf, limit, cursor);
  }
  public Map<String, Object> advices(String from, String to, String kind, String variant, Integer limit, String cursor) {
    return advices(from, to, kind, variant, limit, cursor, true);
  }
  /**
   * 판단 목록. summary(기본)는 헤더 컬럼만(+pick_count), full 은 32컬럼 전체(trend/outlook/regime/guard/diff JSON 포함, 1건 ≈10KB — 2026-10-16 P2). 상세는 adviceDetail.
   */
  public Map<String, Object> advices(String from, String to, String kind, String variant, Integer limit, String cursor, boolean summary) {
    String k = kind == null || kind.isBlank() ? "DAILY" : kind;
    String v = variant == null || variant.isBlank() ? "LIVE" : variant;
    kr.hvy.blog.modules.advisor.domain.code.AdviceKind.valueOf(k);
    kr.hvy.blog.modules.advisor.domain.code.AdviceVariant.valueOf(v);
    Map<String, Object> result = page(new Source("tb_advisor_advice", null, "base_date", "lpad(advice_id::text, 20, '0')",
            summary ? "tb_advisor_advice:summary" : "tb_advisor_advice"),
        "advice_kind = :kind AND variant = :variant", new MapSqlParameterSource("kind", k).addValue("variant", v),
        from, to, limit, cursor);
    result.put("mode", summary ? "summary" : "full");
    if (summary) {
      @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
      List<Long> ids = items.stream().map(i -> ((Number) i.get("advice_id")).longValue()).toList();
      Map<Long, Integer> counts = new LinkedHashMap<>();
      if (!ids.isEmpty()) {
        jdbc.query("SELECT advice_id, COUNT(*) AS n FROM tb_advisor_pick WHERE advice_id IN (:ids) GROUP BY advice_id", new MapSqlParameterSource("ids", ids),
            rs -> { counts.put(rs.getLong("advice_id"), rs.getInt("n")); });
      }
      for (Map<String, Object> item : items) item.put("pick_count", counts.getOrDefault(((Number) item.get("advice_id")).longValue(), 0));
    }
    return result;
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
    return page(source, filter, params, from, to, limit, cursor, false);
  }

  /**
   * 날짜·고유키 순 페이지. descending 이면 최신순(커서 비교·ORDER BY 반전, dataAsOf 는 첫 항목 날짜)이고 응답 order 키로 방향을 알린다. 커서 scope 에 방향이 들어가 섞어 쓸 수 없다.
   */
  private Map<String, Object> page(Source source, String filter, MapSqlParameterSource params, String from,
      String to, Integer limit, String cursor, boolean descending) {
    LocalDate end = end(to);
    LocalDate start = from == null || from.isBlank() ? end.minusDays(90) : LocalDate.parse(from);
    if (start.isAfter(end)) throw new IllegalArgumentException("from 은 to 이전이어야 합니다");
    if (limit != null && limit < 1) throw new IllegalArgumentException("limit must be positive");
    int size = limit == null ? 50 : Math.min(250, limit);
    params.addValue("start", start).addValue("end", end);
    String scope = source.projection + filter + params.getValues() + (descending ? "|desc" : "");
    String after = "";
    if (cursor != null && !cursor.isBlank()) {
      try {
        List<String> decoded = mapper.readValue(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), new TypeReference<List<String>>() {});
        if (decoded.size() != 3 || !scope.equals(decoded.get(0))) throw new IllegalArgumentException();
        LocalDate.parse(decoded.get(1));
        if (decoded.get(2) == null) throw new IllegalArgumentException();
        params.addValue("cursorDate", decoded.get(1)).addValue("cursorKey", decoded.get(2));
        after = " AND ((" + source.date + ")::text, (" + source.key + ")::text) " + (descending ? "<" : ">") + " (:cursorDate, :cursorKey)";
      } catch (RuntimeException e) { throw new IllegalArgumentException("유효하지 않은 cursor"); }
    }
    params.addValue("limit", size + 1);
    String sql = "SELECT to_jsonb(t) AS data, (" + source.date + ")::text AS cursor_date, ("
        + source.key + ")::text AS cursor_key FROM (SELECT " + McpFields.columns(source.projection) + " FROM " + source.table + ") t WHERE " + filter
        + " AND " + source.date + " BETWEEN :start AND :end" + after
        + " ORDER BY (" + source.date + ")::text" + (descending ? " DESC" : "") + ", (" + source.key + ")::text" + (descending ? " DESC" : "") + " LIMIT :limit";
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
    result.put("order", descending ? "desc" : "asc");
    // dataAsOf 는 페이지 안 최대 날짜 — 오름차순이면 마지막, 내림차순이면 첫 항목
    result.put("dataAsOf", items.isEmpty() ? null : mapper.readValue(new String(Base64.getUrlDecoder().decode(cursors.get(descending ? 0 : items.size() - 1)), StandardCharsets.UTF_8), new TypeReference<List<String>>() {}).get(1));
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
      if (List.of("index_code", "sector_code").contains(source.code)) {
        // 2026-10-16(P2): 지수·업종 코드에 업종명·활성 여부(idxcode 마스터)를 붙인다. 마스터에 없는 코드는 code 만
        catalog.put(entry.getKey(), rows("SELECT jsonb_strip_nulls(jsonb_build_object('code', d.c, 'name', im.index_name, 'active', im.is_active)) AS data "
            + "FROM (SELECT DISTINCT " + source.code + " AS c FROM " + source.table + ") d LEFT JOIN tb_stock_index_master im ON im.index_code = d.c ORDER BY d.c",
            new MapSqlParameterSource()));
      } else {
        catalog.put(entry.getKey(), rows("SELECT jsonb_build_object('code', " + source.code + ") AS data FROM "
            + source.table + " GROUP BY " + source.code + " ORDER BY " + source.code, new MapSqlParameterSource()));
      }
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (String table : McpFields.COLUMNS.keySet()) {
      if (table.contains(":")) continue;   // 요약 투영(table:summary)은 같은 테이블의 부분집합이라 카탈로그에 따로 싣지 않는다
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
    result.put("disabled", disabledSwitches());
    result.put("latestAdviceByKind", advice);
    return result;
  }

  /**
   * 꺼진 스위치 목록 [{flag, sources, effect}]. MCP 를 서빙하는 이 프로세스의 설정 기준이다 — 수집기를 다른 프로세스로 띄우면 그쪽 값과 다를 수 있다.
   */
  List<Map<String, Object>> disabledSwitches() {
    List<Map<String, Object>> off = new ArrayList<>();
    for (Switch s : FRESHNESS_SWITCHES) {
      if (!Boolean.TRUE.equals(env.getProperty(s.flag(), Boolean.class, s.defaultValue()))) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flag", s.flag());
        m.put("sources", s.sources());
        m.put("effect", s.effect());
        off.add(m);
      }
    }
    return off;
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
