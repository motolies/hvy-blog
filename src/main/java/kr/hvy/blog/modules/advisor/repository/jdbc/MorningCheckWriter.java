package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아침 점검 저장·조회 (tb_advisor_morning_check, advice 당 1행).
 */
@Repository
@RequiredArgsConstructor
public class MorningCheckWriter {

  private static final String COLUMNS = "check_id, advice_id, run_id, base_date, us_date, gap_kospi, gap_kosdaq, verdict, detail_json, published_at, created_at";

  private final JdbcTemplate jdbc;

  @Transactional
  public long insert(MorningCheckRow r) {
    return jdbc.queryForObject("INSERT INTO tb_advisor_morning_check (advice_id, run_id, base_date, us_date, gap_kospi, gap_kosdaq, verdict, detail_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING check_id", Long.class,
        r.adviceId(), r.runId(), r.baseDate(), r.usDate(), r.gapKospi(), r.gapKosdaq(), r.verdict().getCode(), AdvisorJdbc.jsonb(r.detailJson()));
  }

  @Transactional
  public void markPublished(long checkId, Instant at) {
    jdbc.update("UPDATE tb_advisor_morning_check SET published_at = ? WHERE check_id = ?", AdvisorJdbc.ts(at), checkId);
  }

  public Optional<MorningCheckRow> findByAdvice(long adviceId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_morning_check WHERE advice_id = ?", MAPPER, adviceId).stream().findFirst();
  }

  public List<MorningCheckRow> findRange(LocalDate from, LocalDate to) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_morning_check WHERE base_date BETWEEN ? AND ? ORDER BY base_date", MAPPER, from, to);
  }

  static final RowMapper<MorningCheckRow> MAPPER = (rs, i) -> MorningCheckRow.builder()
      .checkId(rs.getLong("check_id"))
      .adviceId(rs.getLong("advice_id"))
      .runId(AdvisorJdbc.nullableLong(rs, "run_id"))
      .baseDate(rs.getObject("base_date", LocalDate.class))
      .usDate(rs.getObject("us_date", LocalDate.class))
      .gapKospi(AdvisorJdbc.nullableDouble(rs, "gap_kospi"))
      .gapKosdaq(AdvisorJdbc.nullableDouble(rs, "gap_kosdaq"))
      .verdict(AdvisorJdbc.enumOrNull(rs, "verdict", MorningVerdict.class))
      .detailJson(AdvisorJdbc.jsonMap(rs, "detail_json"))
      .publishedAt(AdvisorJdbc.instant(rs, "published_at"))
      .createdAt(AdvisorJdbc.instant(rs, "created_at"))
      .build();
}
