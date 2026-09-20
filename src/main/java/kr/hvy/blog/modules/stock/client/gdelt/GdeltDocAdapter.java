package kr.hvy.blog.modules.stock.client.gdelt;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.client.EventFeedPort;
import kr.hvy.blog.modules.stock.client.GdeltProperties;
import kr.hvy.blog.modules.stock.client.KisJson;
import kr.hvy.blog.modules.stock.client.gdelt.dto.GdeltArticleListResponse;
import kr.hvy.blog.modules.stock.client.gdelt.dto.GdeltTimelineResponse;
import kr.hvy.blog.modules.stock.domain.code.TimelineMode;
import kr.hvy.blog.modules.stock.domain.model.EventTheme;
import kr.hvy.blog.modules.stock.domain.model.EventTimelinePoint;
import kr.hvy.blog.modules.stock.domain.model.FeedArticle;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * GDELT DOC 2.0 API 어댑터. 호출 사이 최소 간격(기본 6초)을 강제하고 429 는 백오프 뒤 재시도한다 — 2026-09-20 실측에서 연속 호출은 즉시
 * "limit requests to one every 5 seconds" 429 를 받았다. 쿼리는 미리 퍼센트 인코딩해 {@link URI} 로 넘긴다(RestClient 가 다시 인코딩하지 않게).
 * 한 스레드(스케줄러)만 부르므로 스로틀은 단순 모니터로 충분하다.
 */
@Slf4j
@Component
public class GdeltDocAdapter implements EventFeedPort {

  static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
  static final DateTimeFormatter SEEN = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final RestClient restClient;
  private final GdeltProperties properties;
  private final Object throttle = new Object();
  private long lastCallAtMs = 0L;

  public GdeltDocAdapter(@Qualifier("gdeltRestClient") RestClient restClient, GdeltProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public SourceFetch<EventTimelinePoint> fetchTimeline(EventTheme theme, TimelineMode mode, LocalDate from, LocalDate to) {
    Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = to.atTime(LocalTime.of(23, 59, 59)).atOffset(ZoneOffset.UTC).toInstant();
    String url = properties.getBaseUrl() + "?query=" + encode(theme.query()) + "&mode=" + mode.getGdeltMode() + "&format=json"
        + "&startdatetime=" + STAMP.format(start) + "&enddatetime=" + STAMP.format(end);
    String body = call(url);
    return new SourceFetch<>(toPoints(KisJson.read(body, GdeltTimelineResponse.class)), 1);
  }

  @Override
  public SourceFetch<FeedArticle> fetchArticles(EventTheme theme, Instant from, Instant until, int maxRecords) {
    String url = properties.getBaseUrl() + "?query=" + encode(theme.query()) + "&mode=artlist&format=json&sort=datedesc"
        + "&maxrecords=" + Math.max(1, maxRecords) + "&startdatetime=" + STAMP.format(from) + "&enddatetime=" + STAMP.format(until);
    String body = call(url);
    return new SourceFetch<>(toArticles(KisJson.read(body, GdeltArticleListResponse.class)), 1);
  }

  /**
   * 응답 → 시계열 점. 첫 series 만 쓴다(모드당 하나). 날짜가 안 읽히는 점은 버린다.
   */
  static List<EventTimelinePoint> toPoints(GdeltTimelineResponse response) {
    List<EventTimelinePoint> points = new ArrayList<>();
    if (response == null || response.timeline() == null || response.timeline().isEmpty()) {
      return points;
    }
    GdeltTimelineResponse.Series series = response.timeline().getFirst();
    if (series == null || series.data() == null) {
      return points;
    }
    for (GdeltTimelineResponse.Point p : series.data()) {
      Instant at = seen(p.date());
      if (at == null || p.value() == null) {
        continue;
      }
      points.add(new EventTimelinePoint(at, p.value(), p.norm() == null ? null : Math.round(p.norm())));
    }
    return points;
  }

  /**
   * 응답 → 기사. 제목·URL·시각 중 하나라도 없으면 버린다.
   */
  static List<FeedArticle> toArticles(GdeltArticleListResponse response) {
    List<FeedArticle> articles = new ArrayList<>();
    if (response == null || response.articles() == null) {
      return articles;
    }
    for (GdeltArticleListResponse.Article a : response.articles()) {
      Instant seenAt = seen(a.seendate());
      if (seenAt == null || a.url() == null || a.url().isBlank() || a.title() == null || a.title().isBlank()) {
        continue;
      }
      articles.add(new FeedArticle(a.url().trim(), a.title().trim(), seenAt, a.domain(), a.language()));
    }
    return articles;
  }

  /**
   * "20260918T233000Z" → Instant. 형식이 다르면 null.
   */
  static Instant seen(String stamp) {
    if (stamp == null || stamp.isBlank()) {
      return null;
    }
    try {
      return Instant.from(SEEN.parse(stamp.trim()));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  static String encode(String query) {
    return URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /**
   * 스로틀 + 429 재시도. 다른 4xx·5xx 는 그대로 던진다(잡이 테마 단위로 격리).
   */
  private String call(String url) {
    int attempt = 0;
    while (true) {
      awaitSlot();
      try {
        return restClient.get().uri(URI.create(url)).header("User-Agent", properties.getUserAgent()).retrieve().body(String.class);
      } catch (HttpStatusCodeException e) {
        if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value() && attempt < properties.getMaxRetries()) {
          attempt++;
          log.warn("GDELT 429 — {}ms 뒤 재시도 {}/{}", properties.getRetryBackoffMs(), attempt, properties.getMaxRetries());
          sleep(properties.getRetryBackoffMs());
          continue;
        }
        throw e;
      }
    }
  }

  /**
   * 직전 호출로부터 최소 간격이 지날 때까지 기다린다.
   */
  private void awaitSlot() {
    synchronized (throttle) {
      long wait = lastCallAtMs + properties.getMinIntervalMs() - System.currentTimeMillis();
      if (wait > 0) {
        sleep(wait);
      }
      lastCallAtMs = System.currentTimeMillis();
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GDELT 호출 대기 중 중단", e);
    }
  }
}
