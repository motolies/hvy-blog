package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 12:00 픽 노트 저장·확정·조회 (tb_advisor_pick_note, note-v1 2026-09-21).
 * <ul>
 *   <li>append-only: 점검 1회 = check 1행 + 노트 N행(UNIQUE(check_id, ticker)). 12:00 관측값을 UPSERT 로 덮어쓰지 않는다</li>
 *   <li>확정({@link #finalize})은 OPEN 행만 1회 — T+5 초과 부호가 12:00 초과 부호와 같으면 CONFIRMED, 다르면 REFUTED. 12:00 초과가 없던 행은 상태를 두고 final_excess 만 적는다</li>
 *   <li>집계 조회({@link #findFinalized})는 (advice, ticker) 별 noted_at 가장 이른 확정 행 1건 — 정규 12:00 점검이 항상 먼저이고 장외·수동 재실행 노트는 기록으로만 남는다.
 *       finalized_at·noted_at 둘 다 cutoff 이하만 돌려 사후 재실행(?baseDate=)에서 미래 확정이 새지 않게 한다(bitemporal)</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class PickNoteRepository {

  private static final String COLUMNS = "note_id, advice_id, check_id, ticker, base_date, noted_at, direction, conviction, open_price, current_price, "
      + "change_rate, gap_rate, since_open_rate, bench_rate, excess_rate, z_score, note_class, deviation, why, hypothesis, tags_json, status, "
      + "final_excess, finalized_at, model, run_id, created_at";

  private static final String INSERT = "INSERT INTO tb_advisor_pick_note (advice_id, check_id, ticker, base_date, noted_at, direction, conviction, "
      + "open_price, current_price, change_rate, gap_rate, since_open_rate, bench_rate, excess_rate, z_score, note_class, deviation, why, hypothesis, "
      + "tags_json, status, model, run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String FINALIZE = "UPDATE tb_advisor_pick_note SET final_excess = ?, finalized_at = ?, "
      + "status = CASE WHEN excess_rate IS NULL THEN status "
      + "              WHEN SIGN(excess_rate) = SIGN(CAST(? AS double precision)) THEN 'CONFIRMED' ELSE 'REFUTED' END "
      + "WHERE advice_id = ? AND ticker = ? AND status = 'OPEN' AND finalized_at IS NULL";

  private final JdbcTemplate jdbc;

  /**
   * 점검 1회의 노트를 일괄 저장한다. 저장한 행 수를 돌려준다.
   */
  @Transactional
  public int insertAll(List<PickNoteRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0;
    }
    int[][] counts = jdbc.batchUpdate(INSERT, rows, rows.size(), (ps, r) -> {
      ps.setLong(1, r.adviceId());
      ps.setLong(2, r.checkId());
      ps.setString(3, r.ticker());
      ps.setObject(4, r.baseDate());
      ps.setObject(5, AdvisorJdbc.ts(r.notedAt()));
      ps.setString(6, r.direction().getCode());
      ps.setDouble(7, r.conviction());
      ps.setObject(8, r.openPrice());
      ps.setObject(9, r.currentPrice());
      ps.setObject(10, r.changeRate());
      ps.setObject(11, r.gapRate());
      ps.setObject(12, r.sinceOpenRate());
      ps.setObject(13, r.benchRate());
      ps.setObject(14, r.excessRate());
      ps.setObject(15, r.zScore());
      ps.setString(16, r.noteClass().getCode());
      ps.setString(17, r.deviation());
      ps.setString(18, r.why());
      ps.setString(19, r.hypothesis());
      ps.setObject(20, r.tags() == null ? null : AdvisorJson.write(r.tags()), Types.OTHER);
      ps.setString(21, (r.status() == null ? PickNoteStatus.OPEN : r.status()).getCode());
      ps.setString(22, r.model());
      ps.setObject(23, r.runId());
    });
    int total = 0;
    for (int[] batch : counts) {
      for (int c : batch) {
        total += c == Statement.SUCCESS_NO_INFO ? 1 : Math.max(c, 0);
      }
    }
    return total;
  }

  /**
   * 판단 1건의 노트 전부 (점검 시각·티커 순).
   */
  public List<PickNoteRow> findByAdvice(long adviceId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_pick_note WHERE advice_id = ? ORDER BY noted_at, ticker", MAPPER, adviceId);
  }

  /**
   * T+5 확정. finalExcessByTicker 는 같은 판단의 LIVE 픽 excess_ret(MISSING 제외). 아직 확정되지 않은 OPEN 행만 1회 갱신하며(재호출은 0건) 갱신 행 수를 돌려준다.
   */
  @Transactional
  public int finalize(long adviceId, Map<String, Double> finalExcessByTicker, Instant now) {
    List<Object[]> args = new ArrayList<>();
    for (Map.Entry<String, Double> e : finalExcessByTicker.entrySet()) {
      if (e.getKey() != null && e.getValue() != null && !e.getValue().isNaN()) {
        args.add(new Object[] {e.getValue(), AdvisorJdbc.ts(now), e.getValue(), adviceId, e.getKey()});
      }
    }
    if (args.isEmpty()) {
      return 0;
    }
    int total = 0;
    for (int c : jdbc.batchUpdate(FINALIZE, args)) {
      total += c == Statement.SUCCESS_NO_INFO ? 1 : Math.max(c, 0);
    }
    return total;
  }

  /**
   * 관리자 조회: 기준일 [from, to](양끝 포함, null 이면 무제한)·상태(null 이면 전부), 최신 점검 순 limit 건.
   */
  public List<PickNoteRow> search(LocalDate from, LocalDate to, PickNoteStatus status, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM tb_advisor_pick_note WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (from != null) {
      sql.append(" AND base_date >= ?");
      args.add(from);
    }
    if (to != null) {
      sql.append(" AND base_date <= ?");
      args.add(to);
    }
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.getCode());
    }
    sql.append(" ORDER BY noted_at DESC, ticker LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  /**
   * 집계용 확정 노트: base_date 가 [fromBaseDate, toBaseDate] 안이고 finalizedCutoff 이전에 확정(CONFIRMED|REFUTED)된 (advice, ticker) 별 가장 이른 노트 1건.
   * noted_at 도 cutoff 이하만 — 사후 재실행에서 미래 관측·확정이 새지 않게 한다. 장외 점검(tags_json.offHours=true, 현재가=종가라 반나절이 아님)은 제외해
   * 정규 12:00 점검보다 이른 수동 재실행이 "가장 이른 행" 으로 들어오지 않게 한다.
   */
  public List<PickNoteRow> findFinalized(LocalDate fromBaseDate, LocalDate toBaseDate, Instant finalizedCutoff) {
    return jdbc.query("SELECT DISTINCT ON (advice_id, ticker) " + COLUMNS + " FROM tb_advisor_pick_note "
            + "WHERE base_date BETWEEN ? AND ? AND status IN ('CONFIRMED', 'REFUTED') AND finalized_at IS NOT NULL AND finalized_at <= ? AND noted_at <= ? "
            + "AND COALESCE((tags_json->>'offHours')::boolean, false) = false "
            + "ORDER BY advice_id, ticker, noted_at", MAPPER,
        fromBaseDate, toBaseDate, AdvisorJdbc.ts(finalizedCutoff), AdvisorJdbc.ts(finalizedCutoff));
  }

  /**
   * 아직 확정 시도가 없는(status OPEN, finalized_at NULL) 노트를 가진 판단 id — ScoreJob 의 확정 누락 보충(NOTE_SWEEP)이 매 run 훑는다.
   * 12:00 초과가 없어 OPEN 으로 남은 행은 finalize 가 finalized_at 을 적으므로 여기 다시 나오지 않는다.
   */
  public List<Long> findAdviceIdsWithOpenNotes() {
    return jdbc.queryForList("SELECT DISTINCT advice_id FROM tb_advisor_pick_note WHERE status = 'OPEN' AND finalized_at IS NULL ORDER BY advice_id",
        Long.class);
  }

  /**
   * 확정이 밀린 노트 수: base_date 가 beforeBaseDate 보다 이르면서 finalized_at 이 아직 없는 행(status 무관 — 12:00 초과가 없던 OPEN 행도 finalize 는 finalized_at 을 적는다).
   * T+5 채점(ScoreJob) 뒤 finalize 가 멈추면 recentOutcomes 빈도표가 조용히 비므로 주간 보고가 이 수를 경보로 드러낸다.
   */
  public int countStaleOpen(LocalDate beforeBaseDate) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tb_advisor_pick_note WHERE finalized_at IS NULL AND base_date < ?", Integer.class, beforeBaseDate);
    return n == null ? 0 : n;
  }

  static final RowMapper<PickNoteRow> MAPPER = (rs, i) -> PickNoteRow.builder()
      .noteId(rs.getLong("note_id"))
      .adviceId(rs.getLong("advice_id"))
      .checkId(rs.getLong("check_id"))
      .ticker(rs.getString("ticker"))
      .baseDate(rs.getObject("base_date", LocalDate.class))
      .notedAt(AdvisorJdbc.instant(rs, "noted_at"))
      .direction(AdvisorJdbc.enumOrNull(rs, "direction", PickDirection.class))
      .conviction(rs.getDouble("conviction"))
      .openPrice(AdvisorJdbc.nullableDouble(rs, "open_price"))
      .currentPrice(AdvisorJdbc.nullableDouble(rs, "current_price"))
      .changeRate(AdvisorJdbc.nullableDouble(rs, "change_rate"))
      .gapRate(AdvisorJdbc.nullableDouble(rs, "gap_rate"))
      .sinceOpenRate(AdvisorJdbc.nullableDouble(rs, "since_open_rate"))
      .benchRate(AdvisorJdbc.nullableDouble(rs, "bench_rate"))
      .excessRate(AdvisorJdbc.nullableDouble(rs, "excess_rate"))
      .zScore(AdvisorJdbc.nullableDouble(rs, "z_score"))
      .noteClass(AdvisorJdbc.enumOrNull(rs, "note_class", PickNoteClass.class))
      .deviation(rs.getString("deviation"))
      .why(rs.getString("why"))
      .hypothesis(rs.getString("hypothesis"))
      .tags(AdvisorJdbc.jsonMap(rs, "tags_json"))
      .status(AdvisorJdbc.enumOrNull(rs, "status", PickNoteStatus.class))
      .finalExcess(AdvisorJdbc.nullableDouble(rs, "final_excess"))
      .finalizedAt(AdvisorJdbc.instant(rs, "finalized_at"))
      .model(rs.getString("model"))
      .runId(AdvisorJdbc.nullableLong(rs, "run_id"))
      .createdAt(AdvisorJdbc.instant(rs, "created_at"))
      .build();
}
