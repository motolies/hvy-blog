package kr.hvy.blog.modules.stock.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import kr.hvy.blog.modules.stock.client.EventFeedPort;
import kr.hvy.blog.modules.stock.client.GdeltProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TimelineMode;
import kr.hvy.blog.modules.stock.domain.model.EventTheme;
import kr.hvy.blog.modules.stock.domain.model.EventTimelinePoint;
import kr.hvy.blog.modules.stock.domain.model.EventTimelineRow;
import kr.hvy.blog.modules.stock.domain.model.FeedArticle;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import kr.hvy.blog.modules.stock.domain.model.SourceFetch;
import kr.hvy.blog.modules.stock.repository.jdbc.EventTimelineWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사건 피드 수집 (NEWS, 화~토 06:40·평일 19:20 KST) — GDELT DOC 2.0. 테마마다 ① 기사량·톤 시계열을 받아 **완결된 UTC 일자**로 묶어
 * tb_stock_event_timeline 에 넣고(LIVE 는 덮어쓰지 않음), ② 라이브일 때만 최근 window-hours 헤드라인을 tb_stock_news(source=GDELT) 에 넣는다.
 * 요청에 startDate 가 있으면 백필 모드(source=BACKFILL, 헤드라인 없음 — 이력 재현이 불가능해 라이브 전용이다).
 * <p>
 * 원 건수는 소스 커버리지 성장이 추세를 만들어 특징에 쓰지 않고, 비율(article/total)과 기사 수 가중 평균 톤만 특징 원천이 된다.
 * 테마 단위로 실패를 격리한다(GDELT 무료 API 는 429·중단이 잦다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdeltCollectJob implements CollectJob {

  public static final String SOURCE_GDELT = "GDELT";
  static final int TITLE_MAX = 500;
  static final int ORIGIN_MAX = 60;
  static final int CATEGORY_MAX = 20;
  static final int SERIAL_MAX = 40;

  private final EventFeedPort port;
  private final EventTimelineWriter timelineWriter;
  private final StockNewsWriter newsWriter;
  private final GdeltProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.NEWS;
  }

  @Override
  public void execute(CollectExecution execution) {
    Instant now = Instant.now();
    LocalDate todayUtc = LocalDate.ofInstant(now, ZoneOffset.UTC);
    boolean backfill = execution.request().startDate() != null;
    String source = backfill ? EventTimelineRow.SOURCE_BACKFILL : EventTimelineRow.SOURCE_LIVE;
    // 완결된 UTC 일자만: 오늘(UTC)은 아직 진행 중이라 제외
    LocalDate to = Optional.ofNullable(execution.request().endDate()).orElse(todayUtc.minusDays(1));
    if (to.isAfter(todayUtc.minusDays(1))) {
      to = todayUtc.minusDays(1);
    }
    LocalDate from = backfill ? execution.request().startDate() : to.minusDays(Math.max(1, properties.getTimelineDays()) - 1L);
    List<EventTheme> themes = properties.themeList();
    execution.putMetadata("mode", source);
    execution.putMetadata("from", from.toString());
    execution.putMetadata("to", to.toString());
    execution.putMetadata("targets", themes.size());
    Map<String, Object> perTheme = new LinkedHashMap<>();
    int articlesInserted = 0;
    int articlesFetched = 0;

    for (EventTheme theme : themes) {
      if (execution.isCancelRequested()) {
        break;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      try {
        int timelineRows = 0;
        for (LocalDate[] chunk : chunks(from, to, backfill ? properties.getBackfillChunkDays() : Integer.MAX_VALUE)) {
          SourceFetch<EventTimelinePoint> volume = port.fetchTimeline(theme, TimelineMode.VOLUME, chunk[0], chunk[1]);
          SourceFetch<EventTimelinePoint> tone = port.fetchTimeline(theme, TimelineMode.TONE, chunk[0], chunk[1]);
          execution.context(theme.code()).stats().getApiCalls().addAndGet(volume.httpCalls() + tone.httpCalls());
          List<EventTimelineRow> rows = aggregate(theme.code(), volume.rows(), tone.rows(), todayUtc, source);
          timelineRows += rows.isEmpty() ? 0 : timelineWriter.upsert(rows);
        }
        m.put("timelineRows", timelineRows);
        if (!backfill) {
          Instant since = now.minusSeconds(properties.getWindowHours() * 3600L);
          SourceFetch<FeedArticle> articles = port.fetchArticles(theme, since, now, properties.getMaxRecords());
          execution.context(theme.code()).stats().getApiCalls().addAndGet(articles.httpCalls());
          List<NewsItem> items = new ArrayList<>();
          for (FeedArticle article : articles.rows()) {
            toNewsItem(theme, article, now).ifPresent(items::add);
          }
          int inserted = items.isEmpty() ? 0 : newsWriter.upsert(items);
          articlesFetched += articles.rows().size();
          articlesInserted += inserted;
          m.put("articlesFetched", articles.rows().size());
          m.put("articlesInserted", inserted);
        }
        execution.addRows(timelineRows);
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.context(theme.code()).stats().getApiFails().incrementAndGet();
        execution.recordFailure(theme.code(), e.getMessage());
        m.put("error", e.getMessage());
        log.warn("사건 피드 수집 실패: theme={}, cause={}", theme.code(), e.getMessage());
      }
      perTheme.put(theme.code(), m);
    }
    execution.addRows(articlesInserted);
    execution.putMetadata("themes", perTheme);
    execution.putMetadata("articlesFetched", articlesFetched);
    execution.putMetadata("articlesInserted", articlesInserted);
    execution.flush();
    log.info("사건 피드 수집: mode={}, from={}, to={}, articles={}/{}, themes={}", source, from, to, articlesInserted, articlesFetched, perTheme);
  }

  /**
   * [from, to] 를 chunkDays 이하 구간으로 자른다(양끝 포함). from > to 면 빈 목록.
   */
  static List<LocalDate[]> chunks(LocalDate from, LocalDate to, int chunkDays) {
    List<LocalDate[]> result = new ArrayList<>();
    if (from == null || to == null || from.isAfter(to)) {
      return result;
    }
    int size = Math.max(1, chunkDays);
    LocalDate start = from;
    while (!start.isAfter(to)) {
      LocalDate end = start.plusDays(size - 1L);
      if (end.isAfter(to)) {
        end = to;
      }
      result.add(new LocalDate[] {start, end});
      start = end.plusDays(1);
    }
    return result;
  }

  /**
   * 시간/일 버킷 → 완결 UTC 일자 행. 기사량은 버킷 합, 전체량은 버킷 norm 합, 톤은 같은 시각 버킷의 기사량 가중 평균(짝이 없으면 단순 평균).
   * {@code todayUtc} 이후(오늘 포함)는 부분일이라 버린다.
   */
  static List<EventTimelineRow> aggregate(String themeCode, List<EventTimelinePoint> volume, List<EventTimelinePoint> tone, LocalDate todayUtc,
      String source) {
    Map<Instant, Double> volumeAt = new HashMap<>();
    Map<LocalDate, double[]> byDay = new TreeMap<>(); // [articleSum, normSum, normMissing, toneWeightedSum, toneWeight, toneSimpleSum, toneCount]
    for (EventTimelinePoint p : volume) {
      LocalDate day = LocalDate.ofInstant(p.at(), ZoneOffset.UTC);
      if (!day.isBefore(todayUtc)) {
        continue;
      }
      volumeAt.put(p.at(), p.value());
      double[] acc = byDay.computeIfAbsent(day, d -> new double[7]);
      acc[0] += p.value();
      if (p.norm() == null) {
        acc[2] = 1;
      } else {
        acc[1] += p.norm();
      }
    }
    for (EventTimelinePoint p : tone) {
      LocalDate day = LocalDate.ofInstant(p.at(), ZoneOffset.UTC);
      if (!day.isBefore(todayUtc)) {
        continue;
      }
      double[] acc = byDay.computeIfAbsent(day, d -> new double[7]);
      Double weight = volumeAt.get(p.at());
      if (weight != null && weight > 0) {
        acc[3] += p.value() * weight;
        acc[4] += weight;
      }
      acc[5] += p.value();
      acc[6] += 1;
    }
    List<EventTimelineRow> rows = new ArrayList<>();
    for (Map.Entry<LocalDate, double[]> e : byDay.entrySet()) {
      double[] a = e.getValue();
      Long articleVol = Math.round(a[0]);
      Long totalVol = a[2] == 1 || a[1] <= 0 ? null : Math.round(a[1]);
      Double ratio = totalVol == null || totalVol == 0 ? null : a[0] / totalVol;
      Double avgTone = a[4] > 0 ? a[3] / a[4] : a[6] > 0 ? a[5] / a[6] : null;
      rows.add(new EventTimelineRow(themeCode, e.getKey(), source, articleVol, totalVol, ratio, avgTone));
    }
    return rows;
  }

  /**
   * 기사 → 뉴스 행. 종목 태그는 없다(빈 배열 → advisor 가 시장 헤드라인 슬롯으로 분류). serial_no 는 URL 해시라 재보고(다른 seendate)를 접는다.
   * 미래 시각(시계 오차 5분 초과)은 버린다.
   */
  static Optional<NewsItem> toNewsItem(EventTheme theme, FeedArticle article, Instant now) {
    if (article == null || article.title() == null || article.title().isBlank() || article.seenDate() == null
        || article.seenDate().isAfter(now.plusSeconds(300))) {
      return Optional.empty();
    }
    String title = article.title().replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    if (title.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(NewsItem.builder()
        .source(SOURCE_GDELT)
        .providerCode(theme.code())
        .serialNo(urlHash(article.url()))
        .publishedAt(article.seenDate())
        .title(cut(title, TITLE_MAX))
        .titleHash(NewsItem.hashTitle(title))
        .categoryCode(cut(article.language(), CATEGORY_MAX))
        .origin(cut(article.domain(), ORIGIN_MAX))
        .tickers(List.of())
        .build());
  }

  /**
   * sha256(url) 16진수 앞 40자.
   */
  static String urlHash(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.trim().getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.substring(0, SERIAL_MAX);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String cut(String value, int max) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.length() <= max ? v : v.substring(0, max);
  }
}
