package kr.hvy.blog.modules.stock.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * KIS API 최종 실패만 tb_stock_kis_api_failure 에 남긴다. 성공 호출은 기록하지 않아 tb_api_log 폭증을 피한다.
 * 기록 실패가 수집 잡을 죽이면 안 되므로 예외는 로그로만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisApiFailureRecorder {

  private static final int BODY_LIMIT = 4000;
  private static final int SUMMARY_LIMIT = 500;
  private static final String INSERT_SQL = "INSERT INTO tb_stock_kis_api_failure "
      + "(run_id, tr_id, target_key, request_summary, http_status, kis_rt_cd, kis_msg_cd, response_body, attempt, occurred_at) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";

  private final JdbcTemplate jdbcTemplate;

  /**
   * 실패 1건을 기록한다.
   */
  public void record(KisCallContext context, String trId, String requestSummary, Integer httpStatus,
      String rtCd, String msgCd, String responseBody, int attempt) {
    try {
      jdbcTemplate.update(INSERT_SQL,
          context.runId(), trId, context.targetKey(),
          StringUtils.abbreviate(requestSummary, SUMMARY_LIMIT),
          httpStatus, rtCd, msgCd,
          StringUtils.abbreviate(responseBody, BODY_LIMIT),
          attempt);
    } catch (Exception e) {
      log.error("KIS 실패 기록 저장 실패(무시): trId={}, target={}, cause={}", trId, context.targetKey(), e.getMessage());
    }
  }
}
