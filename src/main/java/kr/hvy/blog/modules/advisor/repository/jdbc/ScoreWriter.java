package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.CallSubject;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStage;
import kr.hvy.blog.modules.advisor.domain.code.ScoreStatus;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.stock.repository.jdbc.BatchUpsertSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채점 행 upsert (tb_advisor_candidate_score / call_score). 잠정 → 확정 재채점은 같은 키를 덮어쓴다.
 */
@Repository
@RequiredArgsConstructor
public class ScoreWriter {

  private static final String CANDIDATE_UPSERT = "INSERT INTO tb_advisor_candidate_score (advice_id, ticker, horizon_days, stage, status, entry_date, "
      + "entry_price, exit_date, exit_price, ret, dividend_ret, bench_ret, excess_ret, cost_adj_excess, scored_at) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) "
      + "ON CONFLICT (advice_id, ticker, horizon_days) DO UPDATE SET stage = EXCLUDED.stage, status = EXCLUDED.status, "
      + "entry_date = EXCLUDED.entry_date, entry_price = EXCLUDED.entry_price, exit_date = EXCLUDED.exit_date, exit_price = EXCLUDED.exit_price, "
      + "ret = EXCLUDED.ret, dividend_ret = EXCLUDED.dividend_ret, bench_ret = EXCLUDED.bench_ret, excess_ret = EXCLUDED.excess_ret, "
      + "cost_adj_excess = EXCLUDED.cost_adj_excess, scored_at = NOW()";

  private static final String CALL_UPSERT = "INSERT INTO tb_advisor_call_score (advice_id, subject_type, subject_code, horizon_days, stage, status, "
      + "predicted, p_up, base_value, exit_value, actual_ret, bench_ret, band, actual_dir, hit, brier, scored_at) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) "
      + "ON CONFLICT (advice_id, subject_type, subject_code, horizon_days) DO UPDATE SET stage = EXCLUDED.stage, status = EXCLUDED.status, "
      + "predicted = EXCLUDED.predicted, p_up = EXCLUDED.p_up, base_value = EXCLUDED.base_value, exit_value = EXCLUDED.exit_value, "
      + "actual_ret = EXCLUDED.actual_ret, bench_ret = EXCLUDED.bench_ret, band = EXCLUDED.band, actual_dir = EXCLUDED.actual_dir, "
      + "hit = EXCLUDED.hit, brier = EXCLUDED.brier, scored_at = NOW()";

  private final BatchUpsertSupport upsert;
  private final JdbcTemplate jdbc;

  @Transactional
  public int upsertCandidateScores(List<CandidateScoreRow> rows) {
    return upsert.batchUpsert(CANDIDATE_UPSERT, rows, 14, (ps, r) -> {
      ps.setLong(1, r.adviceId());
      ps.setString(2, r.ticker());
      ps.setInt(3, r.horizonDays());
      ps.setString(4, r.stage().getCode());
      ps.setString(5, r.status().getCode());
      ps.setObject(6, r.entryDate());
      ps.setObject(7, r.entryPrice());
      ps.setObject(8, r.exitDate());
      ps.setObject(9, r.exitPrice());
      ps.setObject(10, r.ret());
      ps.setDouble(11, r.dividendRet());
      ps.setObject(12, r.benchRet());
      ps.setObject(13, r.excessRet());
      ps.setObject(14, r.costAdjExcess());
    });
  }

  @Transactional
  public int upsertCallScores(List<CallScoreRow> rows) {
    return upsert.batchUpsert(CALL_UPSERT, rows, 16, (ps, r) -> {
      ps.setLong(1, r.adviceId());
      ps.setString(2, r.subjectType().getCode());
      ps.setString(3, r.subjectCode());
      ps.setInt(4, r.horizonDays());
      ps.setString(5, r.stage().getCode());
      ps.setString(6, r.status().getCode());
      ps.setString(7, r.predicted());
      ps.setObject(8, r.pUp());
      ps.setObject(9, r.baseValue());
      ps.setObject(10, r.exitValue());
      ps.setObject(11, r.actualRet());
      ps.setObject(12, r.benchRet());
      ps.setObject(13, r.band());
      ps.setString(14, r.actualDir());
      ps.setObject(15, r.hit());
      ps.setObject(16, r.brier());
    });
  }

  /**
   * 잠정 상태로 남아 있고 청산일이 지정일 이전인 (advice, horizon) 목록 — 확정 재채점 대상.
   */
  public List<Long> provisionalAdviceIdsExitedBefore(LocalDate before) {
    return jdbc.queryForList("SELECT DISTINCT advice_id FROM tb_advisor_candidate_score WHERE stage = ? AND exit_date < ? ORDER BY advice_id",
        Long.class, ScoreStage.PROVISIONAL.getCode(), before);
  }

  /**
   * 특정 advice 의 채점 행 (모든 호라이즌).
   */
  public List<CandidateScoreRow> candidateScores(long adviceId) {
    return jdbc.query("SELECT advice_id, ticker, horizon_days, stage, status, entry_date, entry_price, exit_date, exit_price, ret, dividend_ret, "
            + "bench_ret, excess_ret, cost_adj_excess FROM tb_advisor_candidate_score WHERE advice_id = ? ORDER BY horizon_days, ticker",
        CANDIDATE_SCORE_MAPPER, adviceId);
  }

  public List<CallScoreRow> callScores(long adviceId) {
    return jdbc.query("SELECT advice_id, subject_type, subject_code, horizon_days, stage, status, predicted, p_up, base_value, exit_value, "
            + "actual_ret, bench_ret, band, actual_dir, hit, brier FROM tb_advisor_call_score WHERE advice_id = ? ORDER BY subject_type, subject_code",
        CALL_SCORE_MAPPER, adviceId);
  }

  static final RowMapper<CandidateScoreRow> CANDIDATE_SCORE_MAPPER = (rs, i) -> CandidateScoreRow.builder()
      .adviceId(rs.getLong("advice_id"))
      .ticker(rs.getString("ticker"))
      .horizonDays(rs.getInt("horizon_days"))
      .stage(AdvisorJdbc.enumOrNull(rs, "stage", ScoreStage.class))
      .status(AdvisorJdbc.enumOrNull(rs, "status", ScoreStatus.class))
      .entryDate(rs.getObject("entry_date", LocalDate.class))
      .entryPrice(AdvisorJdbc.nullableDouble(rs, "entry_price"))
      .exitDate(rs.getObject("exit_date", LocalDate.class))
      .exitPrice(AdvisorJdbc.nullableDouble(rs, "exit_price"))
      .ret(AdvisorJdbc.nullableDouble(rs, "ret"))
      .dividendRet(rs.getDouble("dividend_ret"))
      .benchRet(AdvisorJdbc.nullableDouble(rs, "bench_ret"))
      .excessRet(AdvisorJdbc.nullableDouble(rs, "excess_ret"))
      .costAdjExcess(AdvisorJdbc.nullableDouble(rs, "cost_adj_excess"))
      .build();

  static final RowMapper<CallScoreRow> CALL_SCORE_MAPPER = (rs, i) -> CallScoreRow.builder()
      .adviceId(rs.getLong("advice_id"))
      .subjectType(AdvisorJdbc.enumOrNull(rs, "subject_type", CallSubject.class))
      .subjectCode(rs.getString("subject_code"))
      .horizonDays(rs.getInt("horizon_days"))
      .stage(AdvisorJdbc.enumOrNull(rs, "stage", ScoreStage.class))
      .status(AdvisorJdbc.enumOrNull(rs, "status", ScoreStatus.class))
      .predicted(rs.getString("predicted"))
      .pUp(AdvisorJdbc.nullableDouble(rs, "p_up"))
      .baseValue(AdvisorJdbc.nullableDouble(rs, "base_value"))
      .exitValue(AdvisorJdbc.nullableDouble(rs, "exit_value"))
      .actualRet(AdvisorJdbc.nullableDouble(rs, "actual_ret"))
      .benchRet(AdvisorJdbc.nullableDouble(rs, "bench_ret"))
      .band(AdvisorJdbc.nullableDouble(rs, "band"))
      .actualDir(rs.getString("actual_dir"))
      .hit(AdvisorJdbc.nullableBoolean(rs, "hit"))
      .brier(AdvisorJdbc.nullableDouble(rs, "brier"))
      .build();
}
