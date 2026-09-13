package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장중 점검 저장·조회 (tb_advisor_intraday_check).
 */
@Repository
@RequiredArgsConstructor
public class IntradayCheckWriter {

  private final JdbcTemplate jdbc;

  @Transactional
  public long insert(IntradayCheckRow r) {
    return jdbc.queryForObject("INSERT INTO tb_advisor_intraday_check (advice_id, run_id, checked_at, index_json, pick_json, agreement_ratio, verdict, comment) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING check_id", Long.class,
        r.adviceId(), r.runId(), AdvisorJdbc.ts(r.checkedAt()), AdvisorJdbc.jsonb(r.indexJson()), AdvisorJdbc.jsonb(r.pickJson()),
        r.agreementRatio(), r.verdict().getCode(), r.comment());
  }

  public List<IntradayCheckRow> findByAdvice(long adviceId) {
    return jdbc.query("SELECT check_id, advice_id, run_id, checked_at, index_json, pick_json, agreement_ratio, verdict, comment "
        + "FROM tb_advisor_intraday_check WHERE advice_id = ? ORDER BY checked_at", MAPPER, adviceId);
  }

  static final RowMapper<IntradayCheckRow> MAPPER = (rs, i) -> IntradayCheckRow.builder()
      .checkId(rs.getLong("check_id"))
      .adviceId(rs.getLong("advice_id"))
      .runId(AdvisorJdbc.nullableLong(rs, "run_id"))
      .checkedAt(AdvisorJdbc.instant(rs, "checked_at"))
      .indexJson(AdvisorJdbc.jsonMap(rs, "index_json"))
      .pickJson(AdvisorJdbc.jsonListOfMap(rs, "pick_json"))
      .agreementRatio(AdvisorJdbc.nullableDouble(rs, "agreement_ratio"))
      .verdict(AdvisorJdbc.enumOrNull(rs, "verdict", IntradayVerdict.class))
      .comment(rs.getString("comment"))
      .build();
}
