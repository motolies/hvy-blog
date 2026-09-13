package kr.hvy.blog.modules.stock.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisNewsTitleResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 종합 시황/공시 제목 수집 (NEWS, 평일 08:05~19:35 30분 간격). 전체 시황 피드를 최신순으로 받아(tr_cont 연속조회) tb_stock_news 에 넣는다.
 * <p>
 * "실시간" 은 하루 1회 19:30 판단에 가치가 없으므로 30분 주기면 충분하다. 저장 기준 시각은 기사 작성 시각(data_dt+data_tm, KST) 이며 advisor 는
 * 그 값으로 판단 시각 이전 기사만 고른다(룩어헤드 방어). 이미 저장된 최신 작성 시각(−1h 완충)보다 오래된 행은 버린다(증분).
 * 메타 {@code fetched → candidates → inserted} 깔때기에 받은 행의 작성 시각 경계 {@code newest/oldest} 를 함께 남긴다 — 2026-09-13 운영에서
 * 잘못된 요청 필터로 열흘 넘게 오래된 40행만 받아 0건이 났을 때 개수만으로는 원인을 알 수 없었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCollectJob implements CollectJob {

  public static final String SOURCE_KIS = "KIS";
  static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

  private final KisMarketDataPort marketDataPort;
  private final StockNewsWriter newsWriter;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.NEWS;
  }

  @Override
  public void execute(CollectExecution execution) {
    ZonedDateTime now = MarketClock.now();
    Instant floor = now.minusHours(properties.getNews().getLookbackHours()).toInstant();
    Instant latest = newsWriter.latestPublishedAt(SOURCE_KIS);
    // 증분 하한: 마지막 저장 기사보다 1시간 앞까지는 다시 받아 늦게 들어온 기사를 흡수한다
    Instant since = latest == null ? floor : latest.minusSeconds(3600).isAfter(floor) ? latest.minusSeconds(3600) : floor;

    List<KisNewsTitleResponse.Row> rows = marketDataPort.fetchNewsTitles(null, properties.getNews().getMaxPages(), execution.context("NEWS"));
    List<NewsItem> items = new ArrayList<>();
    int skippedOld = 0;
    int unparsed = 0;
    Instant newest = null;
    Instant oldest = null;
    for (KisNewsTitleResponse.Row row : rows) {
      Optional<NewsItem> item = toItem(row, now.toInstant());
      if (item.isEmpty()) {
        unparsed++;
        continue;
      }
      Instant at = item.get().publishedAt();
      newest = newest == null || at.isAfter(newest) ? at : newest;
      oldest = oldest == null || at.isBefore(oldest) ? at : oldest;
      if (at.isBefore(since)) {
        skippedOld++;
        continue;
      }
      items.add(item.get());
    }
    int inserted = items.isEmpty() ? 0 : newsWriter.upsert(items);
    execution.addRows(inserted);
    execution.targetDone();
    execution.putMetadata("fetched", rows.size());
    execution.putMetadata("candidates", items.size());
    execution.putMetadata("inserted", inserted);
    execution.putMetadata("skippedOld", skippedOld);
    execution.putMetadata("unparsed", unparsed);
    execution.putMetadata("since", since.toString());
    if (newest != null) {
      execution.putMetadata("newest", newest.toString());
      execution.putMetadata("oldest", oldest.toString());
    }
    log.info("뉴스 제목 수집: fetched={} candidates={} inserted={} skippedOld={} unparsed={} since={} newest={} oldest={}",
        rows.size(), items.size(), inserted, skippedOld, unparsed, since, newest, oldest);
    if (!rows.isEmpty() && items.isEmpty()) {
      // 받았는데 하나도 못 쓰는 경우는 정상 공백이 아니라 요청 필터·필드명·시계 문제일 가능성이 높다
      log.warn("뉴스 제목 수집 0건: 받은 {}행이 모두 증분 하한 밖이거나 파싱 불가 (newest={}, oldest={}, since={}, unparsed={}) — kis.news.* 요청 필터·응답 필드명을 확인",
          rows.size(), newest, oldest, since, unparsed);
    }
  }

  /**
   * 응답 1행 → 저장 행. 제목·작성 시각이 없거나 미래(시계 오차 5분 초과)면 버린다. 종목코드는 6자리 숫자만 인정한다.
   */
  static Optional<NewsItem> toItem(KisNewsTitleResponse.Row row, Instant now) {
    if (row == null || row.title() == null || row.title().isBlank()) {
      return Optional.empty();
    }
    Instant publishedAt = parse(row.date(), row.time());
    if (publishedAt == null || publishedAt.isAfter(now.plusSeconds(300))) {
      return Optional.empty();
    }
    Set<String> tickers = new LinkedHashSet<>();
    for (String code : List.of(nz(row.iscd1()), nz(row.iscd2()), nz(row.iscd3()), nz(row.iscd4()), nz(row.iscd5()))) {
      String trimmed = code.trim();
      if (trimmed.matches("\\d{6}")) {
        tickers.add(trimmed);
      }
    }
    String title = row.title().trim().replaceAll("[\\p{Cntrl}]", " ");
    return Optional.of(NewsItem.builder().source(SOURCE_KIS).providerCode(row.providerCode()).serialNo(row.serialNo()).publishedAt(publishedAt)
        .title(title.length() > 500 ? title.substring(0, 500) : title).titleHash(NewsItem.hashTitle(title)).categoryCode(row.categoryCode())
        .origin(row.origin()).tickers(new ArrayList<>(tickers)).build());
  }

  /**
   * data_dt(yyyyMMdd) + data_tm(HHmmss, 짧으면 앞을 0 으로) → KST Instant. 형식이 다르면 null.
   */
  static Instant parse(String date, String time) {
    if (date == null || date.isBlank()) {
      return null;
    }
    try {
      LocalDate d = LocalDate.parse(date.trim(), DATE);
      String t = time == null ? "" : time.trim();
      LocalTime lt = t.isEmpty() ? LocalTime.MIDNIGHT : LocalTime.parse(String.format("%6s", t).replace(' ', '0'), TIME);
      return LocalDateTime.of(d, lt).atZone(MarketClock.KST).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
