package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.LessonScope;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교훈 저장·조회 (tb_advisor_lesson).
 */
@Repository
@RequiredArgsConstructor
public class LessonRepository {

  private static final String COLUMNS = "lesson_id, status, scope, condition_json, observation, evidence_json, rule, lesson_text, applied_count, "
      + "post_n_applied, post_excess_applied, post_n_not_applied, post_excess_not_applied, activated_at, retired_at, retired_reason, model, run_id, created_at";

  private final JdbcTemplate jdbc;

  public List<LessonRow> findByStatus(LessonStatus status) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_lesson WHERE status = ? ORDER BY created_at DESC", MAPPER, status.getCode());
  }

  public List<LessonRow> findAll(int limit) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_lesson ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
  }

  public Optional<LessonRow> find(long lessonId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_lesson WHERE lesson_id = ?", MAPPER, lessonId).stream().findFirst();
  }

  @Transactional
  public long insert(LessonRow l) {
    return jdbc.queryForObject("INSERT INTO tb_advisor_lesson (status, scope, condition_json, observation, evidence_json, rule, lesson_text, "
            + "applied_count, activated_at, model, run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING lesson_id", Long.class,
        l.status().getCode(), l.scope().getCode(), AdvisorJdbc.jsonb(l.condition()), l.observation(), AdvisorJdbc.jsonb(l.evidence()),
        l.rule(), l.lessonText(), l.appliedCount(), AdvisorJdbc.ts(l.activatedAt()), l.model(), l.runId());
  }

  @Transactional
  public void updateStatus(long lessonId, LessonStatus status, Instant at, String reason) {
    if (status == LessonStatus.ACTIVE) {
      jdbc.update("UPDATE tb_advisor_lesson SET status = ?, activated_at = ?, retired_at = NULL, retired_reason = NULL WHERE lesson_id = ?",
          status.getCode(), AdvisorJdbc.ts(at), lessonId);
    } else if (status == LessonStatus.RETIRED) {
      jdbc.update("UPDATE tb_advisor_lesson SET status = ?, retired_at = ?, retired_reason = ? WHERE lesson_id = ?",
          status.getCode(), AdvisorJdbc.ts(at), reason, lessonId);
    } else {
      jdbc.update("UPDATE tb_advisor_lesson SET status = ? WHERE lesson_id = ?", status.getCode(), lessonId);
    }
  }

  /**
   * 적용 누적 수를 더한다 (ADVISE 가 condition 이 참인 후보 수만큼).
   */
  @Transactional
  public void addApplied(long lessonId, int count) {
    if (count > 0) {
      jdbc.update("UPDATE tb_advisor_lesson SET applied_count = applied_count + ? WHERE lesson_id = ?", count, lessonId);
    }
  }

  /**
   * 활성 후 적용/비적용 픽의 성과를 갱신한다 (주간 검토).
   */
  @Transactional
  public void updatePostPerformance(long lessonId, int nApplied, Double excessApplied, int nNotApplied, Double excessNotApplied) {
    jdbc.update("UPDATE tb_advisor_lesson SET post_n_applied = ?, post_excess_applied = ?, post_n_not_applied = ?, post_excess_not_applied = ? "
        + "WHERE lesson_id = ?", nApplied, excessApplied, nNotApplied, excessNotApplied, lessonId);
  }

  static final RowMapper<LessonRow> MAPPER = (rs, i) -> LessonRow.builder()
      .lessonId(rs.getLong("lesson_id"))
      .status(AdvisorJdbc.enumOrNull(rs, "status", LessonStatus.class))
      .scope(AdvisorJdbc.enumOrNull(rs, "scope", LessonScope.class))
      .condition(AdvisorJdbc.jsonMap(rs, "condition_json"))
      .observation(rs.getString("observation"))
      .evidence(AdvisorJdbc.jsonMap(rs, "evidence_json"))
      .rule(rs.getString("rule"))
      .lessonText(rs.getString("lesson_text"))
      .appliedCount(rs.getInt("applied_count"))
      .postNApplied(AdvisorJdbc.nullableInt(rs, "post_n_applied"))
      .postExcessApplied(AdvisorJdbc.nullableDouble(rs, "post_excess_applied"))
      .postNNotApplied(AdvisorJdbc.nullableInt(rs, "post_n_not_applied"))
      .postExcessNotApplied(AdvisorJdbc.nullableDouble(rs, "post_excess_not_applied"))
      .activatedAt(AdvisorJdbc.instant(rs, "activated_at"))
      .retiredAt(AdvisorJdbc.instant(rs, "retired_at"))
      .retiredReason(rs.getString("retired_reason"))
      .model(rs.getString("model"))
      .runId(AdvisorJdbc.nullableLong(rs, "run_id"))
      .createdAt(AdvisorJdbc.instant(rs, "created_at"))
      .build();
}
