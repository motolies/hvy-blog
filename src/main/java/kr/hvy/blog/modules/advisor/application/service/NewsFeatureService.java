package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.NewsBlock;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 프롬프트 news 블록 조립(advice-v4). 판단 시각(min(now, 기준일 cutoff KST)) 이전 windowHours 창의 제목을 읽어
 * 시장 헤드라인(종목 태그 없음) ≤ marketLimit, 후보 종목별 ≤ perTickerLimit, 전체 ≤ totalLimit 로 고르고 N1… id 를 붙인다.
 * <p>
 * 룩어헤드 방어는 기사 작성 시각(published_at ≤ 판단 시각)이며, 사후 재실행에서도 cutoff 가 상한이라 미래 기사가 들어가지 않는다.
 * 제목은 Slack 멘션·제어문자를 지우고 길이를 자른다(프롬프트 인젝션·토큰 방어). 제목 원문은 프롬프트 입력 스냅샷에만 남고 Slack 에는 나가지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NewsFeatureService {

  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
  /** 창 안에서 읽는 행 상한 (그 이상은 최신순으로 버린다) */
  static final int FETCH_LIMIT = 600;

  private final StockNewsWriter newsWriter;
  private final AdvisorProperties properties;

  /**
   * 기준일 판단에 넣을 뉴스 블록. 창 안에 제목이 하나도 없으면 empty.
   *
   * @param candidateTickers 후보 종목코드 (byTicker 대상)
   */
  public Optional<NewsBlock> news(LocalDate baseDate, List<String> candidateTickers) {
    Instant until = judgmentInstant(baseDate);
    Instant from = until.minusSeconds(properties.getNews().getWindowHours() * 3600L);
    List<NewsItem> rows = newsWriter.findPublishedBetween(from, until, FETCH_LIMIT);
    return Optional.ofNullable(select(rows, candidateTickers, until, properties.getNews()));
  }

  /**
   * 판단 시각: 지금과 기준일 cutoff(KST) 중 이른 쪽.
   */
  Instant judgmentInstant(LocalDate baseDate) {
    Instant cutoff = ZonedDateTime.of(baseDate, properties.getNews().getCutoff(), MarketClock.KST).toInstant();
    Instant now = Instant.now();
    return now.isBefore(cutoff) ? now : cutoff;
  }

  /**
   * 선별 규칙(순수 함수): 최신순 입력에서 시장 헤드라인과 후보별 헤드라인을 상한대로 고르고, 전체 상한을 넘으면 후보별 목록의 뒤(오래된 것)부터 뺀다.
   * id 는 시장 → 후보(후보 순서) 순으로 N1… 을 붙이되 같은 기사가 여러 종목에 붙으면 같은 id 를 쓴다.
   */
  static NewsBlock select(List<NewsItem> rows, List<String> candidateTickers, Instant asOf, AdvisorProperties.News cfg) {
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    Set<String> candidates = new LinkedHashSet<>(candidateTickers == null ? List.of() : candidateTickers);
    List<NewsItem> market = new ArrayList<>();
    Map<String, List<NewsItem>> byTicker = new LinkedHashMap<>();
    for (NewsItem row : rows) {
      List<String> tickers = row.tickers() == null ? List.of() : row.tickers();
      if (tickers.isEmpty()) {
        if (market.size() < cfg.getMarketLimit()) {
          market.add(row);
        }
        continue;
      }
      for (String ticker : tickers) {
        if (!candidates.contains(ticker)) {
          continue;
        }
        List<NewsItem> list = byTicker.computeIfAbsent(ticker, k -> new ArrayList<>());
        if (list.size() < cfg.getPerTickerLimit()) {
          list.add(row);
        }
      }
    }
    // 전체 상한: 후보별 목록의 뒤(오래된 것)부터 하나씩 뺀다
    int total = market.size() + byTicker.values().stream().mapToInt(List::size).sum();
    while (total > cfg.getTotalLimit()) {
      NewsItem oldest = null;
      List<NewsItem> owner = null;
      for (List<NewsItem> list : byTicker.values()) {
        if (!list.isEmpty()) {
          NewsItem last = list.getLast();
          if (oldest == null || last.publishedAt().isBefore(oldest.publishedAt())) {
            oldest = last;
            owner = list;
          }
        }
      }
      if (owner == null) {
        market.removeLast();
      } else {
        owner.removeLast();
      }
      total--;
    }
    byTicker.values().removeIf(List::isEmpty);
    if (market.isEmpty() && byTicker.isEmpty()) {
      return null;
    }
    // id 부여 (같은 기사 = 같은 id)
    Map<Long, String> ids = new LinkedHashMap<>();
    List<NewsBlock.Headline> marketHeadlines = new ArrayList<>();
    for (NewsItem item : market) {
      marketHeadlines.add(headline(item, ids, cfg));
    }
    Map<String, List<NewsBlock.Headline>> tickerHeadlines = new LinkedHashMap<>();
    for (String ticker : candidates) {
      List<NewsItem> list = byTicker.get(ticker);
      if (list == null) {
        continue;
      }
      List<NewsBlock.Headline> hs = new ArrayList<>();
      list.forEach(item -> hs.add(headline(item, ids, cfg)));
      tickerHeadlines.put(ticker, hs);
    }
    return new NewsBlock(asOf, cfg.getWindowHours(), marketHeadlines, tickerHeadlines);
  }

  private static NewsBlock.Headline headline(NewsItem item, Map<Long, String> ids, AdvisorProperties.News cfg) {
    String id = ids.computeIfAbsent(item.newsId() == null ? (long) System.identityHashCode(item) : item.newsId(), k -> "N" + (ids.size() + 1));
    String time = item.publishedAt().atZone(MarketClock.KST).format(TIME);
    return new NewsBlock.Headline(id, time, sanitize(item.title(), cfg.getTitleChars()), item.tickers() == null ? List.of() : item.tickers());
  }

  /**
   * 제목 정리: Slack 멘션·제어문자·연속 공백 제거, 길이 절단.
   */
  static String sanitize(String title, int max) {
    if (title == null) {
      return "";
    }
    String cleaned = title.replace("<!channel>", "").replace("<!here>", "").replace("<!everyone>", "")
        .replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 1) + "…";
  }
}
