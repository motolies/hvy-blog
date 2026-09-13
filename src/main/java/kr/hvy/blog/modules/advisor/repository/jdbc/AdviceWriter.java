package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CitedFeature;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * 판단 헤더·후보·픽 저장과 조회 (tb_advisor_advice / candidate / pick). JPA 대신 JdbcTemplate — JSONB 스냅샷이 많고 감사 컬럼이 불필요하다.
 */
@Repository
@RequiredArgsConstructor
public class AdviceWriter {

  private static final TypeReference<Map<String, SignalValue>> SIGNALS = new TypeReference<>() {
  };
  private static final TypeReference<List<SectorCall>> SECTORS = new TypeReference<>() {
  };
  private static final TypeReference<List<CitedFeature>> CITED = new TypeReference<>() {
  };

  private static final TypeReference<List<MarketTrend>> TRENDS = new TypeReference<>() {
  };
  private static final TypeReference<List<TrendOutlook>> OUTLOOKS = new TypeReference<>() {
  };

  private static final String HEADER_COLUMNS = "advice_id, run_id, base_date, advice_kind, variant, horizon_days, regime_code, kospi_dir, "
      + "kosdaq_dir, p_up, regime_rationale, leading_sectors, summary, trend_kospi, trend_kosdaq, trend_json, outlook_json, data_as_of_json, "
      + "entry_date, exit_date, prompt_version, model, system_fingerprint, weight_set_id, "
      + "active_lesson_ids, data_quality, guard_json, published_at, created_at";

  private final JdbcTemplate jdbc;

  /**
   * 헤더를 저장하고 advice_id 를 돌려준다. 같은 (base_date, kind, variant) 가 있으면 유니크 제약으로 실패한다 — 재실행은 먼저 {@link #delete} 한다.
   */
  @Transactional
  public long insertHeader(AdviceHeader h) {
    return jdbc.queryForObject(
        "INSERT INTO tb_advisor_advice (run_id, base_date, advice_kind, variant, horizon_days, regime_code, kospi_dir, kosdaq_dir, p_up, "
            + "regime_rationale, leading_sectors, summary, trend_kospi, trend_kosdaq, trend_json, outlook_json, data_as_of_json, entry_date, exit_date, "
            + "prompt_version, model, system_fingerprint, weight_set_id, active_lesson_ids, data_quality, guard_json, published_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING advice_id",
        Long.class,
        h.runId(), h.baseDate(), h.adviceKind(), h.variant().getCode(), h.horizonDays(),
        code(h.regimeCode()), code(h.kospiDir()), code(h.kosdaqDir()), h.pUp(),
        h.regimeRationale(), AdvisorJdbc.jsonb(h.leadingSectors()), h.summary(),
        code(h.trendKospi()), code(h.trendKosdaq()), AdvisorJdbc.jsonb(h.trends()), AdvisorJdbc.jsonb(h.outlooks()), AdvisorJdbc.jsonb(h.dataAsOf()),
        h.entryDate(), h.exitDate(),
        h.promptVersion(), h.model(), h.systemFingerprint(),
        h.weightSetId(), AdvisorJdbc.jsonb(h.activeLessonIds()),
        (h.dataQuality() == null ? DataQuality.OK : h.dataQuality()).getCode(), AdvisorJdbc.jsonb(h.guard()), AdvisorJdbc.ts(h.publishedAt()));
  }

  /**
   * 후보 스냅샷을 저장한다 (advice 당 1회, 재실행은 advice 삭제 후).
   */
  @Transactional
  public int insertCandidates(long adviceId, List<CandidateRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    int[][] counts = jdbc.batchUpdate(
        "INSERT INTO tb_advisor_candidate (advice_id, ticker, quant_rank, quant_score, stock_name, market_type, bench_index_code, "
            + "sector_code, sector_name, signal_json, feature_json, applied_lesson_ids, ref_raw_close, ref_adj_close) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows, rows.size(), (ps, r) -> {
          ps.setLong(1, adviceId);
          ps.setString(2, r.ticker());
          ps.setInt(3, r.quantRank());
          ps.setDouble(4, r.quantScore());
          ps.setString(5, r.stockName());
          ps.setString(6, r.marketType());
          ps.setString(7, r.benchIndexCode());
          ps.setString(8, r.sectorCode());
          ps.setString(9, r.sectorName());
          ps.setObject(10, AdvisorJson.write(r.signals() == null ? Map.of() : r.signals()), java.sql.Types.OTHER);
          ps.setObject(11, r.features() == null ? null : AdvisorJson.write(r.features()), java.sql.Types.OTHER);
          ps.setObject(12, r.appliedLessonIds() == null ? null : AdvisorJson.write(r.appliedLessonIds()), java.sql.Types.OTHER);
          ps.setBigDecimal(13, r.refRawClose());
          ps.setObject(14, r.refAdjClose());
        });
    return sum(counts);
  }

  private static int sum(int[][] counts) {
    int total = 0;
    for (int[] chunk : counts) {
      for (int c : chunk) {
        total += Math.max(c, 0);
      }
    }
    return total;
  }

  /**
   * 픽을 저장한다. 후보 FK 가 후보 밖 티커를 막는다(가드 뒤 2차 방어).
   */
  @Transactional
  public int insertPicks(long adviceId, List<PickRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    int[][] counts = jdbc.batchUpdate(
        "INSERT INTO tb_advisor_pick (advice_id, ticker, pick_rank, direction, conviction, thesis, risk_note, cited_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        rows, rows.size(), (ps, r) -> {
          ps.setLong(1, adviceId);
          ps.setString(2, r.ticker());
          ps.setInt(3, r.pickRank());
          ps.setString(4, r.direction().getCode());
          ps.setDouble(5, r.conviction());
          ps.setString(6, r.thesis());
          ps.setString(7, r.riskNote());
          ps.setObject(8, r.cited() == null ? null : AdvisorJson.write(r.cited()), java.sql.Types.OTHER);
        });
    return sum(counts);
  }

  /**
   * 발행 시각을 기록한다.
   */
  @Transactional
  public void markPublished(long adviceId, Instant at) {
    jdbc.update("UPDATE tb_advisor_advice SET published_at = ? WHERE advice_id = ?", AdvisorJdbc.ts(at), adviceId);
  }

  /**
   * advice 와 그에 딸린 후보·픽·채점·장중 점검을 지운다 (ON DELETE CASCADE). 강제 재실행용.
   */
  @Transactional
  public int delete(long adviceId) {
    return jdbc.update("DELETE FROM tb_advisor_advice WHERE advice_id = ?", adviceId);
  }

  public Optional<AdviceHeader> find(LocalDate baseDate, String adviceKind, AdviceVariant variant) {
    List<AdviceHeader> rows = jdbc.query("SELECT " + HEADER_COLUMNS + " FROM tb_advisor_advice WHERE base_date = ? AND advice_kind = ? AND variant = ?",
        HEADER_MAPPER, baseDate, adviceKind, variant.getCode());
    return rows.stream().findFirst();
  }

  public Optional<AdviceHeader> findById(long adviceId) {
    return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM tb_advisor_advice WHERE advice_id = ?", HEADER_MAPPER, adviceId)
        .stream().findFirst();
  }

  /**
   * base_date 가 가장 최근인 LIVE 판단 (장중 점검·스코어보드용). 지정일 이하만.
   */
  public Optional<AdviceHeader> findLatest(AdviceVariant variant, LocalDate onOrBefore) {
    return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM tb_advisor_advice WHERE variant = ? AND base_date <= ? "
            + "ORDER BY base_date DESC LIMIT 1", HEADER_MAPPER, variant.getCode(), onOrBefore)
        .stream().findFirst();
  }

  /**
   * 기간·변형별 헤더 목록 (최신순).
   */
  public List<AdviceHeader> findRange(LocalDate from, LocalDate to, AdviceVariant variant, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + HEADER_COLUMNS + " FROM tb_advisor_advice WHERE base_date BETWEEN ? AND ?");
    java.util.ArrayList<Object> args = new java.util.ArrayList<>(List.of(from, to));
    if (variant != null) {
      sql.append(" AND variant = ?");
      args.add(variant.getCode());
    }
    sql.append(" ORDER BY base_date DESC, variant LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), HEADER_MAPPER, args.toArray());
  }

  /**
   * 채점 대상 헤더: base_date 가 정확히 그 날인 모든 변형.
   */
  public List<AdviceHeader> findByBaseDate(LocalDate baseDate) {
    return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM tb_advisor_advice WHERE base_date = ? ORDER BY variant", HEADER_MAPPER, baseDate);
  }

  public List<CandidateRow> candidates(long adviceId) {
    return jdbc.query("SELECT ticker, quant_rank, quant_score, stock_name, market_type, bench_index_code, sector_code, sector_name, "
            + "signal_json, feature_json, applied_lesson_ids, ref_raw_close, ref_adj_close FROM tb_advisor_candidate WHERE advice_id = ? "
            + "ORDER BY quant_rank", CANDIDATE_MAPPER, adviceId);
  }

  public List<PickRow> picks(long adviceId) {
    return jdbc.query("SELECT ticker, pick_rank, direction, conviction, thesis, risk_note, cited_json FROM tb_advisor_pick "
        + "WHERE advice_id = ? ORDER BY pick_rank", PICK_MAPPER, adviceId);
  }

  /**
   * 누적 LIVE 픽 수 (피드백 게이트 판정용).
   */
  public int countLivePicks() {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_pick p JOIN tb_advisor_advice a ON a.advice_id = p.advice_id "
        + "WHERE a.variant = 'LIVE'", Integer.class);
    return n == null ? 0 : n;
  }

  private static String code(Enum<?> value) {
    return value == null ? null : value.name();
  }

  static final RowMapper<AdviceHeader> HEADER_MAPPER = (rs, i) -> AdviceHeader.builder()
      .adviceId(rs.getLong("advice_id"))
      .runId(rs.getLong("run_id"))
      .baseDate(rs.getObject("base_date", LocalDate.class))
      .adviceKind(rs.getString("advice_kind"))
      .variant(AdvisorJdbc.enumOrNull(rs, "variant", AdviceVariant.class))
      .horizonDays(rs.getInt("horizon_days"))
      .regimeCode(AdvisorJdbc.enumOrNull(rs, "regime_code", MarketRegimeCode.class))
      .kospiDir(AdvisorJdbc.enumOrNull(rs, "kospi_dir", DirectionCall.class))
      .kosdaqDir(AdvisorJdbc.enumOrNull(rs, "kosdaq_dir", DirectionCall.class))
      .pUp(AdvisorJdbc.nullableDouble(rs, "p_up"))
      .regimeRationale(rs.getString("regime_rationale"))
      .leadingSectors(readSectors(rs))
      .summary(rs.getString("summary"))
      .trendKospi(AdvisorJdbc.enumOrNull(rs, "trend_kospi", MarketTrendCode.class))
      .trendKosdaq(AdvisorJdbc.enumOrNull(rs, "trend_kosdaq", MarketTrendCode.class))
      .trends(readJson(rs, "trend_json", TRENDS))
      .outlooks(readJson(rs, "outlook_json", OUTLOOKS))
      .dataAsOf(AdvisorJdbc.jsonMap(rs, "data_as_of_json"))
      .entryDate(rs.getObject("entry_date", LocalDate.class))
      .exitDate(rs.getObject("exit_date", LocalDate.class))
      .promptVersion(rs.getString("prompt_version"))
      .model(rs.getString("model"))
      .systemFingerprint(rs.getString("system_fingerprint"))
      .weightSetId(AdvisorJdbc.nullableLong(rs, "weight_set_id"))
      .activeLessonIds(AdvisorJdbc.jsonListOfLong(rs, "active_lesson_ids"))
      .dataQuality(AdvisorJdbc.enumOrNull(rs, "data_quality", DataQuality.class))
      .guard(AdvisorJdbc.jsonMap(rs, "guard_json"))
      .publishedAt(AdvisorJdbc.instant(rs, "published_at"))
      .createdAt(AdvisorJdbc.instant(rs, "created_at"))
      .build();

  private static List<SectorCall> readSectors(ResultSet rs) throws SQLException {
    String json = rs.getString("leading_sectors");
    return json == null || json.isBlank() ? List.of() : AdvisorJson.MAPPER.readValue(json, SECTORS);
  }

  /**
   * JSONB 목록 컬럼을 레코드 목록으로. NULL 이면 빈 목록.
   */
  private static <T> List<T> readJson(ResultSet rs, String column, TypeReference<List<T>> type) throws SQLException {
    String json = rs.getString(column);
    return json == null || json.isBlank() ? List.of() : AdvisorJson.MAPPER.readValue(json, type);
  }

  static final RowMapper<CandidateRow> CANDIDATE_MAPPER = (rs, i) -> CandidateRow.builder()
      .ticker(rs.getString("ticker"))
      .quantRank(rs.getInt("quant_rank"))
      .quantScore(rs.getDouble("quant_score"))
      .stockName(rs.getString("stock_name"))
      .marketType(rs.getString("market_type"))
      .benchIndexCode(rs.getString("bench_index_code"))
      .sectorCode(rs.getString("sector_code"))
      .sectorName(rs.getString("sector_name"))
      .signals(readSignals(rs))
      .features(AdvisorJdbc.jsonMap(rs, "feature_json"))
      .appliedLessonIds(AdvisorJdbc.jsonListOfLong(rs, "applied_lesson_ids"))
      .refRawClose(rs.getBigDecimal("ref_raw_close"))
      .refAdjClose(AdvisorJdbc.nullableDouble(rs, "ref_adj_close"))
      .build();

  private static Map<String, SignalValue> readSignals(ResultSet rs) throws SQLException {
    String json = rs.getString("signal_json");
    return json == null || json.isBlank() ? Map.of() : AdvisorJson.MAPPER.readValue(json, SIGNALS);
  }

  static final RowMapper<PickRow> PICK_MAPPER = (rs, i) -> PickRow.builder()
      .ticker(rs.getString("ticker"))
      .pickRank(rs.getInt("pick_rank"))
      .direction(AdvisorJdbc.enumOrNull(rs, "direction", PickDirection.class))
      .conviction(rs.getDouble("conviction"))
      .thesis(rs.getString("thesis"))
      .riskNote(rs.getString("risk_note"))
      .cited(readCited(rs))
      .build();

  private static List<CitedFeature> readCited(ResultSet rs) throws SQLException {
    String json = rs.getString("cited_json");
    return json == null || json.isBlank() ? List.of() : AdvisorJson.MAPPER.readValue(json, CITED);
  }

  /** 후보 목록의 티커 집합 (가드·FK 사전 검증용) */
  public static java.util.Set<String> tickers(List<CandidateRow> candidates) {
    return candidates.stream().map(CandidateRow::ticker).collect(Collectors.toSet());
  }
}
