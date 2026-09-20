package kr.hvy.blog.modules.stock.client;

import java.time.Instant;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.domain.code.TimelineMode;
import kr.hvy.blog.modules.stock.domain.model.EventTheme;
import kr.hvy.blog.modules.stock.domain.model.EventTimelinePoint;
import kr.hvy.blog.modules.stock.domain.model.FeedArticle;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;

/**
 * 사건 피드(GDELT) 외부 경계. 시계열(기사량·톤)은 이력이 있어 백필·검증이 가능하고, 헤드라인은 라이브 전용이다.
 */
public interface EventFeedPort {

  /**
   * 테마 쿼리의 [from, to] (UTC 일자, 양끝 포함) 시계열. 버킷 해상도는 API 가 창 길이에 따라 정하므로(시간/일) 호출부가 일자로 묶는다.
   */
  SourceFetch<EventTimelinePoint> fetchTimeline(EventTheme theme, TimelineMode mode, LocalDate from, LocalDate to);

  /**
   * 테마 쿼리의 (from, until] 헤드라인 최신순 최대 maxRecords 건.
   */
  SourceFetch<FeedArticle> fetchArticles(EventTheme theme, Instant from, Instant until, int maxRecords);
}
