package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * GET /api/stats/admin/traffic — 트래픽 위젯 전체.
 * <p>
 * 델타(어제 대비)를 함께 싣는 이유: 비교값 없는 절대수는 아무 판단도 만들지 못한다.
 * "오늘 방문자 42"가 아니라 "42, 어제 대비 +18%"여야 화면이 쓸모 있다.
 */
@Value
@Builder
@Jacksonized
public class TrafficStats {

  /** 집계에 적용된 클라이언트 타임존. 표시·디버깅용. */
  String timeZone;
  LocalDate fromDate;
  LocalDate toDate;

  /** 빈 날짜까지 채운 상태로 반환한다. */
  List<DailyTraffic> dailyTrend;

  long todayVisitors;
  long yesterdayVisitors;
  Double visitorDeltaPercent;

  long todayRequests;
  long yesterdayRequests;
  Double requestDeltaPercent;

  long todayPostViews;
  long yesterdayPostViews;
  Double postViewDeltaPercent;

  /** 누적 view_count 기준 — 역대 인기 글. */
  List<PopularPost> popularPostsAllTime;
  /** beacon 로그 기준 — 최근 기간에 실제로 읽힌 글. */
  List<RecentPopularPost> popularPostsRecent;
  List<RequestUriStat> topRequestUris;

  /**
   * 조회수 수집이 시작된 시각(= 가장 오래된 beacon 로그). null 이면 아직 한 건도 안 들어온 것.
   * <p>
   * 이 필드가 없으면 프론트가 "아무도 안 읽었다"와 "아직 측정을 안 하고 있다"를 구분할 수 없다.
   * beacon 배포 직후의 평평한 0 선은 정상인데, 그걸 버그로 오해하면 멀쩡한 차트를 "고치게" 된다.
   */
  Instant collectionStartedAt;
}
