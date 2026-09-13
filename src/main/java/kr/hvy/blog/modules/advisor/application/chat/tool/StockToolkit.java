package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.MetricColumn;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader.MetricRank;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader.Snapshot;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader.StockHit;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.NewsItem;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher.AdjustedClose;
import kr.hvy.blog.modules.stock.repository.jdbc.StockNewsWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 종목 도구 5종 — 해석·스냅샷·가격 시계열·지표 상위 N·뉴스 제목. 가격 정본은 수정 종가(vw_stock_daily_price_adj / tb_stock_daily_metric.adj_close)뿐이다.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class StockToolkit {

  static final int RESOLVE_LIMIT = 8;
  static final int SERIES_MAX_ROWS = 250;
  static final int SERIES_DEFAULT_DAYS = 60;
  static final int TOPN_CAP = 20;
  static final int NEWS_CAP = 20;
  static final int NEWS_MAX_HOURS = 72;
  static final int NEWS_DEFAULT_HOURS = 36;
  static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MM-dd");
  static final DateTimeFormatter NEWS_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

  private final ToolSupport support;
  private final StockLookupReader reader;
  private final DerivedViewRefresher prices;
  private final StockNewsWriter news;

  @Tool(name = "resolveStock", description = "사용자가 한글 종목명(일부라도)이나 6자리 코드로 물었을 때 종목코드를 찾는다. 다른 종목 도구(stockSnapshot·priceSeries)를 쓰기 전에 "
      + "반드시 먼저 호출한다. 여러 종목이 걸리면 후보를 사용자에게 보여 주고 되묻는다. grp: ST 주권, EF ETF, EN ETN, RT 리츠. act=false 는 상장폐지.")
  public Map<String, Object> resolveStock(@ToolParam(description = "종목명 일부 또는 6자리 종목코드 (예: 삼성전자, 005930)") String query, ToolContext context) {
    return support.run("resolveStock", context, () -> {
      List<StockHit> hits = reader.resolve(query, RESOLVE_LIMIT);
      if (hits.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_NO_DATA, "'" + query + "' 와 일치하는 종목이 없다", null);
      }
      Map<String, Object> m = ToolJson.obj();
      m.put("query", query);
      m.put("n", hits.size());
      List<Map<String, Object>> items = new ArrayList<>();
      for (StockHit h : hits) {
        Map<String, Object> x = ToolJson.obj();
        x.put("tk", h.ticker());
        x.put("nm", h.name());
        x.put("mkt", h.market());
        x.put("grp", h.group());
        x.put("act", h.active());
        ToolJson.put(x, "delisted", h.delistingDate() == null ? null : h.delistingDate().toString());
        items.add(x);
      }
      m.put("items", items);
      return m;
    });
  }

  @Tool(name = "stockSnapshot", description = "종목 하나의 기준일 스냅샷: 수정 종가, 1/5/20/60/120일 수익률, MA 5/20/60/120, MA20·MA60 이격, 52주 고가와 이격, 거래대금 5일 평균과 "
      + "5일/60일 비율(거래 급증), 외국인·기관 5일 순매수(억원), 시가총액(억원)·PER·PBR·외인지분율, 섹터. '삼성전자 요즘 어때?' 류 질문에 쓴다. 종목코드는 resolveStock 으로 먼저 확인한다.")
  public Map<String, Object> stockSnapshot(
      @ToolParam(description = "6자리 종목코드") String ticker,
      @ToolParam(required = false, description = MarketToolkit.AS_OF_DESC) String asOf, ToolContext context) {
    return support.run("stockSnapshot", context, () -> {
      Optional<LocalDate> date = support.asOf(asOf);
      if (date.isEmpty()) {
        return ToolJson.noData(null);
      }
      Optional<Snapshot> found = reader.snapshot(clean(ticker), date.get());
      if (found.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_NO_DATA, "종목 " + ticker + " 의 지표가 없다 (코드 확인: resolveStock)", date.get());
      }
      Snapshot s = found.get();
      support.noteAsOf(context, s.tradeDate());
      Map<String, Object> m = ToolJson.obj();
      m.put("tk", s.ticker());
      m.put("nm", s.name());
      m.put("mkt", s.market());
      m.put("asOf", s.tradeDate().toString());
      ToolJson.put(m, "close", ToolJson.r0(s.adjClose()));
      ToolJson.put(m, "r1", ToolJson.r4(s.ret1d()));
      ToolJson.put(m, "r5", ToolJson.r4(s.ret5d()));
      ToolJson.put(m, "r20", ToolJson.r4(s.ret20d()));
      ToolJson.put(m, "r60", ToolJson.r4(s.ret60d()));
      ToolJson.put(m, "r120", ToolJson.r4(s.ret120d()));
      ToolJson.put(m, "ma5", ToolJson.r0(s.ma5()));
      ToolJson.put(m, "ma20", ToolJson.r0(s.ma20()));
      ToolJson.put(m, "ma60", ToolJson.r0(s.ma60()));
      ToolJson.put(m, "ma120", ToolJson.r0(s.ma120()));
      ToolJson.put(m, "distMa20", ToolJson.r4(s.distMa20()));
      ToolJson.put(m, "distMa60", ToolJson.r4(s.distMa60()));
      ToolJson.put(m, "high52w", ToolJson.r0(s.high52w()));
      ToolJson.put(m, "distHigh52w", ToolJson.r4(s.distHigh52w()));
      ToolJson.put(m, "tvAvg5dEok", eok(s.tvAvg5d()));
      ToolJson.put(m, "tvRatio5_60", ToolJson.r4(s.tvRatio560()));
      ToolJson.put(m, "frgnNet5dEok", eok(s.foreignNet5d()));
      ToolJson.put(m, "instNet5dEok", eok(s.institutionNet5d()));
      ToolJson.put(m, "marketCapEok", s.marketCap() == null ? null : Math.round(s.marketCap() / 1e8));
      ToolJson.put(m, "per", ToolJson.r4(s.per()));
      ToolJson.put(m, "pbr", ToolJson.r4(s.pbr()));
      ToolJson.put(m, "foreignHoldRate", ToolJson.r4(s.foreignHoldRate()));
      ToolJson.put(m, "valuationAsOf", s.valuationDate() == null ? null : s.valuationDate().toString());
      ToolJson.put(m, "sector", s.sectorName() == null ? s.sectorCode() : s.sectorName());
      m.put("note", "수익률·이격은 비율(0.031 = 3.1%), 가격은 수정 종가(원). σ·RSI 는 제공하지 않는다");
      return m;
    });
  }

  @Tool(name = "priceSeries", description = "종목의 수정 종가 시계열(거래일 순). days 개(기본 60, 최대 250)를 to 기준일까지 가져온다. d 는 MM-dd 날짜 배열, c 는 종가 배열(같은 순서). "
      + "'최근 흐름', '고점 대비', '얼마나 올랐나' 를 숫자로 설명할 때 stockSnapshot 과 함께 쓴다.")
  public Map<String, Object> priceSeries(
      @ToolParam(description = "6자리 종목코드") String ticker,
      @ToolParam(required = false, description = "기준일(마지막 날) yyyy-MM-dd. 생략하면 마지막 거래일") String to,
      @ToolParam(required = false, description = "거래일 수 1~250 (기본 60)") Integer days, ToolContext context) {
    return support.run("priceSeries", context, () -> {
      Optional<LocalDate> end = support.asOf(to);
      if (end.isEmpty()) {
        return ToolJson.noData(null);
      }
      int wanted = days == null || days <= 0 ? SERIES_DEFAULT_DAYS : Math.min(days, SERIES_MAX_ROWS);
      // 캘린더일로 넉넉히 읽고 뒤에서 wanted 개만 남긴다(거래일/캘린더일 ≈ 5/7)
      LocalDate from = end.get().minusDays((long) wanted * 7 / 5 + 10);
      List<AdjustedClose> rows = prices.adjustedCloses(clean(ticker), from, end.get());
      if (rows.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_NO_DATA, "종목 " + ticker + " 의 가격이 없다 (코드 확인: resolveStock)", end.get());
      }
      if (rows.size() > wanted) {
        rows = rows.subList(rows.size() - wanted, rows.size());
      }
      support.noteAsOf(context, rows.getLast().tradeDate());
      Map<String, Object> m = ToolJson.obj();
      m.put("tk", clean(ticker));
      m.put("from", rows.getFirst().tradeDate().toString());
      m.put("to", rows.getLast().tradeDate().toString());
      m.put("n", rows.size());
      m.put("d", rows.stream().map(r -> r.tradeDate().format(MMDD)).toList());
      m.put("c", rows.stream().map(r -> r.adjClose() == null ? null : Math.round(r.adjClose().doubleValue())).toList());
      return m;
    });
  }

  @Tool(name = "metricTopN", description = "기준 거래일에 지표 하나로 정렬한 상위 N 종목(주권·활성·시총 1,000억 이상·거래대금 10억 이상 유니버스). "
      + "column 은 ret_1d, ret_5d, ret_20d(20일 모멘텀), ret_60d, ret_120d, dist_ma20, dist_ma60, dist_high_52w, tv_ratio_5_60(거래 급증), foreign_net_5d, institution_net_5d 중 하나. "
      + "'20일 모멘텀 상위 10개', '외국인이 많이 산 종목' 류 질문에 쓴다. 값은 비율(수익률·이격)이거나 원(순매수).")
  public Map<String, Object> metricTopN(
      @ToolParam(description = "정렬 컬럼 (예: ret_20d)") String column,
      @ToolParam(required = false, description = "desc(기본, 큰 값부터) 또는 asc(작은 값부터)") String order,
      @ToolParam(required = false, description = "KOSPI 또는 KOSDAQ. 생략하면 양시장") String market,
      @ToolParam(required = false, description = "종목 수 1~20 (기본 10)") Integer limit,
      @ToolParam(required = false, description = MarketToolkit.AS_OF_DESC) String asOf, ToolContext context) {
    return support.run("metricTopN", context, () -> {
      Optional<MetricColumn> col = MetricColumn.parse(column);
      if (col.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_BAD_ARGUMENT, "지원하지 않는 컬럼 '" + column + "'. 허용: " + MetricColumn.allowedList(), null);
      }
      String mkt = market == null || market.isBlank() ? null : market.trim().toUpperCase();
      if (mkt != null && !mkt.equals("KOSPI") && !mkt.equals("KOSDAQ")) {
        return ToolJson.error(ToolJson.ERROR_BAD_ARGUMENT, "market 은 KOSPI 또는 KOSDAQ", null);
      }
      Optional<LocalDate> date = support.asOf(asOf);
      if (date.isEmpty()) {
        return ToolJson.noData(null);
      }
      boolean descending = order == null || !order.trim().equalsIgnoreCase("asc");
      int n = support.clampLimit(limit == null ? 10 : limit, TOPN_CAP);
      List<MetricRank> rows = reader.topN(col.get(), descending, mkt, n, date.get());
      if (rows.isEmpty()) {
        return ToolJson.noData(date.get());
      }
      support.noteAsOf(context, date.get());
      Map<String, Object> m = ToolJson.obj();
      m.put("asOf", date.get().toString());
      m.put("col", col.get().getColumn());
      m.put("desc", col.get().getDesc());
      m.put("order", descending ? "desc" : "asc");
      ToolJson.put(m, "market", mkt);
      m.put("n", rows.size());
      List<Map<String, Object>> items = new ArrayList<>();
      for (MetricRank r : rows) {
        Map<String, Object> x = ToolJson.obj();
        x.put("tk", r.ticker());
        x.put("nm", r.name());
        x.put("mkt", r.market());
        ToolJson.put(x, "v", isMoney(col.get()) ? eok(r.value()) : ToolJson.r4(r.value()));
        items.add(x);
      }
      m.put("items", items);
      m.put("unit", isMoney(col.get()) ? "억원" : "비율");
      return m;
    });
  }

  @Tool(name = "newsHeadlines", description = "최근 hours 시간(기본 36, 최대 72) 안의 국내 시황·공시 뉴스 제목(본문 없음). ticker 를 주면 그 종목이 태깅된 기사만. "
      + "수집이 꺼져 있으면 빈 목록이 정상이며, 그때는 '뉴스 데이터가 없다' 고 답한다. 제목 원문을 그대로 길게 옮기지 말고 요약한다.")
  public Map<String, Object> newsHeadlines(
      @ToolParam(required = false, description = "6자리 종목코드 (생략하면 전체)") String ticker,
      @ToolParam(required = false, description = "조회 시간 창 1~72 (기본 36)") Integer hours, ToolContext context) {
    return support.run("newsHeadlines", context, () -> {
      int h = hours == null || hours <= 0 ? NEWS_DEFAULT_HOURS : Math.min(hours, NEWS_MAX_HOURS);
      Instant until = Instant.now();
      Instant from = until.minusSeconds(h * 3_600L);
      String tk = ticker == null || ticker.isBlank() ? null : clean(ticker);
      List<NewsItem> rows = news.findPublishedBetween(from, until, 300);
      List<Map<String, Object>> items = new ArrayList<>();
      for (NewsItem item : rows) {
        if (tk != null && (item.tickers() == null || !item.tickers().contains(tk))) {
          continue;
        }
        Map<String, Object> x = ToolJson.obj();
        x.put("t", item.publishedAt() == null ? null : item.publishedAt().atZone(MarketClock.KST).format(NEWS_TIME));
        x.put("ttl", item.title());
        ToolJson.put(x, "tk", item.tickers() == null || item.tickers().isEmpty() ? null : item.tickers());
        items.add(x);
        if (items.size() >= NEWS_CAP) {
          break;
        }
      }
      Map<String, Object> m = ToolJson.obj();
      m.put("asOf", until.atZone(MarketClock.KST).toLocalDateTime().withNano(0).toString());
      m.put("hours", h);
      ToolJson.put(m, "tk", tk);
      m.put("n", items.size());
      m.put("items", items);
      if (items.isEmpty()) {
        m.put("note", "해당 창에 뉴스 데이터가 없다(수집이 꺼져 있을 수 있음)");
      }
      return m;
    });
  }

  static String clean(String ticker) {
    return ticker == null ? "" : ticker.trim().toUpperCase();
  }

  static boolean isMoney(MetricColumn column) {
    return column == MetricColumn.FOREIGN_NET_5D || column == MetricColumn.INSTITUTION_NET_5D;
  }

  /**
   * 원 → 억원(소수 1자리).
   */
  static Double eok(Double won) {
    return won == null ? null : Math.round(won / 1e7) / 10.0;
  }
}
