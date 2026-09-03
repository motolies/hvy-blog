package kr.hvy.blog.modules.stock.application.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 운영 테이블 보관주기. LogCleanerScheduler 가 호출한다. 시계열 정본은 절대 지우지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StockRetentionService {

  private final JdbcTemplate jdbcTemplate;

  /**
   * KIS 실패 기록 삭제.
   */
  @Transactional
  public int deleteFailuresOlderThan(Instant cutoff) {
    return jdbcTemplate.update("DELETE FROM tb_stock_kis_api_failure WHERE occurred_at < ?", java.sql.Timestamp.from(cutoff));
  }

  /**
   * 종료된 run 삭제 (RUNNING 은 남긴다).
   */
  @Transactional
  public int deleteFinishedRunsOlderThan(Instant cutoff) {
    return jdbcTemplate.update("DELETE FROM tb_stock_collect_run WHERE finished_at IS NOT NULL AND finished_at < ?",
        java.sql.Timestamp.from(cutoff));
  }
}
