package kr.hvy.blog.modules.stats.repository.mapper;

import java.time.Instant;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.DailyTraffic;
import kr.hvy.blog.modules.stats.application.dto.EndpointLatency;
import kr.hvy.blog.modules.stats.application.dto.ErrorSummary;
import kr.hvy.blog.modules.stats.application.dto.ExternalApiStat;
import kr.hvy.blog.modules.stats.application.dto.RecentError;
import kr.hvy.blog.modules.stats.application.dto.RecentPopularPost;
import kr.hvy.blog.modules.stats.application.dto.RequestUriStat;
import kr.hvy.blog.modules.stats.application.dto.SchedulerLockRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 로그 기반 집계 — tb_system_log / tb_api_log / shedlock.
 * <p>
 * ⚠️ 이 매퍼의 메서드는 스칼라 파라미터만 받는다. PageInterceptor 는 파라미터가 PageRequest 이거나
 * Map 안에 PageRequest 가 있을 때만 개입하므로, 여기서는 ORDER BY / LIMIT 을 XML 에 직접 써야 한다.
 */
@Mapper
public interface StatsLogMapper {

  /** 일별 요청·방문자·조회 3계열을 1패스로. zoneId 기준 날짜 경계로 그룹핑한다. */
  List<DailyTraffic> findDailyTraffic(@Param("from") Instant from,
      @Param("to") Instant to, @Param("zoneId") String zoneId);

  /** 최근 기간 인기 글 — beacon 로그 기반. idx_system_log_post_view 부분 인덱스를 탄다. */
  List<RecentPopularPost> findRecentPopularPosts(@Param("from") Instant from,
      @Param("to") Instant to, @Param("limit") int limit);

  /** 가장 오래된 beacon 로그 시각. null 이면 조회수 수집이 아직 시작되지 않은 것. */
  Instant findViewCollectionStartedAt();

  /** 경로별 트래픽 Top N. 숫자 세그먼트를 {id} 로 정규화해 집계한다. */
  List<RequestUriStat> findTopRequestUris(@Param("from") Instant from,
      @Param("to") Instant to, @Param("limit") int limit);

  /** 최근 창 / 직전 창 요청·에러 건수를 1패스로. */
  ErrorSummary findErrorSummary(@Param("previousFrom") Instant previousFrom,
      @Param("recentFrom") Instant recentFrom, @Param("now") Instant now);

  /** 최근 에러 N건. stack_trace 원문이 아니라 첫 줄만 잘라 온다. */
  List<RecentError> findRecentErrors(@Param("from") Instant from, @Param("limit") int limit);

  /** 엔드포인트별 p95 응답시간. minSamples 미만은 일회성 outlier 라 제외한다. */
  List<EndpointLatency> findSlowEndpoints(@Param("from") Instant from, @Param("to") Instant to,
      @Param("minSamples") int minSamples, @Param("limit") int limit);

  /** shedlock 전체 행 (5개 내외). */
  List<SchedulerLockRow> findSchedulerLocks();

  /** 외부 API 실패 집계 — 실패가 1건 이상인 경로만. */
  List<ExternalApiStat> findExternalApiFailures(@Param("from") Instant from,
      @Param("limit") int limit);
}
