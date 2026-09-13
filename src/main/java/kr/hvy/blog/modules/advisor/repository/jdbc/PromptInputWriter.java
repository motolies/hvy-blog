package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 입력·출력 원문 스냅샷 저장·조회 (tb_advisor_prompt_input).
 */
@Repository
@RequiredArgsConstructor
public class PromptInputWriter {

  private final JdbcTemplate jdbc;

  @Transactional
  public void upsert(PromptInputRow r) {
    jdbc.update("INSERT INTO tb_advisor_prompt_input (run_id, variant, prompt_version, system_sha256, user_payload, options_json, raw_output) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (run_id, variant) DO UPDATE SET prompt_version = EXCLUDED.prompt_version, "
            + "system_sha256 = EXCLUDED.system_sha256, user_payload = EXCLUDED.user_payload, options_json = EXCLUDED.options_json, "
            + "raw_output = EXCLUDED.raw_output",
        r.runId(), r.variant().getCode(), r.promptVersion(), r.systemSha256(), AdvisorJdbc.jsonbRaw(r.userPayload()),
        AdvisorJdbc.jsonbRaw(r.optionsJson()), AdvisorJdbc.jsonbRaw(r.rawOutput()));
  }

  public Optional<PromptInputRow> find(long runId, AdviceVariant variant) {
    return jdbc.query("SELECT run_id, variant, prompt_version, system_sha256, user_payload::text AS user_payload, options_json::text AS options_json, "
            + "raw_output::text AS raw_output FROM tb_advisor_prompt_input WHERE run_id = ? AND variant = ?",
        (rs, i) -> new PromptInputRow(rs.getLong("run_id"), AdvisorJdbc.enumOrNull(rs, "variant", AdviceVariant.class),
            rs.getString("prompt_version"), rs.getString("system_sha256"), rs.getString("user_payload"), rs.getString("options_json"),
            rs.getString("raw_output")),
        runId, variant.getCode()).stream().findFirst();
  }

  /**
   * 보존 기간이 지난 스냅샷을 지운다.
   */
  @Transactional
  public int deleteOlderThan(int days) {
    return jdbc.update("DELETE FROM tb_advisor_prompt_input WHERE created_at < NOW() - make_interval(days => ?)", days);
  }
}
