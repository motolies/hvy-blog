package kr.hvy.blog.modules.stock.domain.model;

import java.time.Instant;

/**
 * GDELT 시계열 버킷 1개. 창이 짧으면 시간 단위, 길면 일 단위로 오므로 일별 집계는 잡이 UTC 일자로 다시 묶는다.
 *
 * @param value VOLUME 이면 테마 기사 수, TONE 이면 평균 톤
 * @param norm  VOLUME 에서만: 같은 버킷의 전체 모니터 기사 수 (비율의 분모). TONE 은 null
 */
public record EventTimelinePoint(Instant at, double value, Long norm) {
}
