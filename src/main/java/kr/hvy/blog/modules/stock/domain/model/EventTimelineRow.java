package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;

/**
 * 테마별 일별 사건 스트레스 행 (tb_stock_event_timeline). obs_date 는 완결된 UTC 일자만이다.
 *
 * @param source     LIVE(운영 수집, 재계산으로 덮어쓰지 않음) | BACKFILL(이력 재구성, 덮어쓰기 허용)
 * @param articleVol 테마 기사 수(원 건수 — 특징에는 쓰지 않고 감사용)
 * @param totalVol   전체 모니터 기사 수
 * @param volRatio   articleVol / totalVol
 * @param avgTone    기사 수 가중 평균 톤
 */
public record EventTimelineRow(String themeCode, LocalDate obsDate, String source, Long articleVol, Long totalVol, Double volRatio, Double avgTone) {

  public static final String SOURCE_LIVE = "LIVE";
  public static final String SOURCE_BACKFILL = "BACKFILL";
}
