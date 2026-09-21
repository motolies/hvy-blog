package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import kr.hvy.blog.modules.advisor.domain.model.NewsBlock;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판단 모델 입력 JSON 조립. 키는 짧게, null 은 생략, 실수는 4자리 반올림, 후보는 columns/rows 표 형태로 넣어 토큰을 아낀다(30×16 ≈ 2,700 토큰).
 * <p>
 * 길이 상한(advisor.prompt.max-input-chars ≈ 8k 토큰)을 넘으면 후보를 뒤에서(점수 낮은 순) 잘라내고 truncated 를 표시한다.
 * 완성된 JSON 문자열을 ChatClient.user(String) 에 그대로 넘긴다 — 템플릿 변수로 넘기면 JSON 의 {} 가 변수로 해석돼 조용히 깨진다.
 * <p>
 * 표 컬럼(SECTOR_COLUMNS·CANDIDATE_COLUMNS)은 위치 배열이라 재현성 측정(동결 페이로드 재실행)·테스트가 위치로 읽는다 — 새 열은 맨 뒤에만 붙인다.
 * advice-v6: sectors 에 업종 지수 rs5/rs20/rs60·mom·consistent·overheated, candidates 에 소속 섹터의 secRs60·secCons(LLM 이 두 표를 조인하지 않게).
 */
@Component
@RequiredArgsConstructor
public class AdvicePromptBuilder {

  static final List<String> CANDIDATE_COLUMNS = List.of("tkr", "name", "sec", "score", "r20", "r60", "distHigh", "tvRatio", "frgnFlow",
      "instFlow", "rsIdx", "per", "pbr", "vol20", "secRs60", "secCons");
  static final List<String> SECTOR_COLUMNS = List.of("code", "name", "cw5", "rising", "nearHigh", "frgn5", "members",
      "rs5", "rs20", "rs60", "mom", "consistent", "overheated");

  private final AdvisorProperties properties;

  /**
   * @param scoreboard 실적 블록(없으면 null) — 픽 n 게이트를 넘긴 뒤에만 주입
   * @param lessons    활성 교훈(없으면 빈 목록)
   * @param weights    적용 가중치 (code → weight)
   * @param quality    게이트가 판정한 데이터 품질 (DEGRADED 면 프롬프트가 확신을 낮추게 한다, null 은 OK)
   */
  public PromptPayload build(MarketFeatures market, ScreeningResult screening, Map<String, Object> scoreboard, List<LessonRow> lessons,
      Map<String, Double> weights, DataQuality quality) {
    return build(market, screening, scoreboard, lessons, weights, quality, null);
  }

  /**
   * @param news 뉴스 블록(없으면 null). news 전용 자 상한(advisor.news.max-chars)을 넘으면 후보별 → 시장 순으로 먼저 줄이고, 그 뒤에야 후보 행을 자른다
   *             — 뉴스가 후보를 밀어내지 않게.
   */
  public PromptPayload build(MarketFeatures market, ScreeningResult screening, Map<String, Object> scoreboard, List<LessonRow> lessons,
      Map<String, Double> weights, DataQuality quality, NewsBlock news) {
    return build(market, screening, scoreboard, lessons, weights, quality, news, null);
  }

  /**
   * @param recentOutcomes 12:00 노트의 T+5 확정 빈도표(note-v1, 없으면 null) — RecentOutcomesService 가 만든 결정론 표만. 루트 키는 scoreboard 뒤·lessons 앞
   */
  public PromptPayload build(MarketFeatures market, ScreeningResult screening, Map<String, Object> scoreboard, List<LessonRow> lessons,
      Map<String, Double> weights, DataQuality quality, NewsBlock news, Map<String, Object> recentOutcomes) {
    NewsBlock fitted = fitNews(news);
    int limit = screening.candidates().size();
    while (true) {
      List<CandidateRow> included = screening.candidates().subList(0, limit);
      String json = AdvisorJson.write(payload(market, screening, included, scoreboard, lessons, weights, quality, fitted, recentOutcomes));
      if (json.length() <= properties.getPrompt().getMaxInputChars() || limit <= Math.max(properties.getPickMin(), 5)) {
        List<String> tickers = included.stream().map(CandidateRow::ticker).toList();
        // 주도 섹터 enum 은 후보가 있는 섹터만(advice-v6) — top·bottom 표는 맥락으로 남지만 후보 없는 섹터(특히 bottom)를 고를 수 없게 스키마에서 막는다
        LinkedHashSet<String> sectors = new LinkedHashSet<>();
        included.stream().map(CandidateRow::sectorCode).filter(c -> c != null).forEach(sectors::add);
        return new PromptPayload(json, tickers, new ArrayList<>(sectors), included.size(), limit < screening.candidates().size(),
            json.length() / 3, fitted == null ? List.of() : fitted.ids());
      }
      limit -= 5;
    }
  }

  /**
   * news 블록을 자 상한 안으로: (후보별 3, 시장 12) → (2, 12) → (1, 12) → (1, 6) → (0, 6) → 없음.
   */
  NewsBlock fitNews(NewsBlock news) {
    if (news == null) {
      return null;
    }
    int max = properties.getNews().getMaxChars();
    int perTicker = properties.getNews().getPerTickerLimit();
    int market = properties.getNews().getMarketLimit();
    NewsBlock current = news;
    while (current != null && AdvisorJson.write(newsBlock(current)).length() > max) {
      if (perTicker > 1) {
        perTicker--;
      } else if (market > 6) {
        market = 6;
      } else if (perTicker == 1) {
        perTicker = 0;
      } else {
        return null;
      }
      current = news.trimmed(perTicker, market);
    }
    return current;
  }

  /**
   * news 블록 JSON: [id, "MM-dd HH:mm", 제목] 표 형태로 토큰을 아낀다.
   */
  static Map<String, Object> newsBlock(NewsBlock news) {
    Map<String, Object> n = new LinkedHashMap<>();
    n.put("asOf", news.asOf().toString());
    n.put("windowHours", news.windowHours());
    n.put("columns", List.of("id", "time", "title"));
    n.put("market", news.market().stream().map(h -> List.of(h.id(), h.time(), h.title())).toList());
    Map<String, Object> byTicker = new LinkedHashMap<>();
    news.byTicker().forEach((t, list) -> byTicker.put(t, list.stream().map(h -> List.of(h.id(), h.time(), h.title())).toList()));
    n.put("byTicker", byTicker);
    return n;
  }

  Map<String, Object> payload(MarketFeatures market, ScreeningResult screening, List<CandidateRow> candidates, Map<String, Object> scoreboard,
      List<LessonRow> lessons, Map<String, Double> weights, DataQuality quality) {
    return payload(market, screening, candidates, scoreboard, lessons, weights, quality, null);
  }

  Map<String, Object> payload(MarketFeatures market, ScreeningResult screening, List<CandidateRow> candidates, Map<String, Object> scoreboard,
      List<LessonRow> lessons, Map<String, Double> weights, DataQuality quality, NewsBlock news) {
    return payload(market, screening, candidates, scoreboard, lessons, weights, quality, news, null);
  }

  Map<String, Object> payload(MarketFeatures market, ScreeningResult screening, List<CandidateRow> candidates, Map<String, Object> scoreboard,
      List<LessonRow> lessons, Map<String, Double> weights, DataQuality quality, NewsBlock news, Map<String, Object> recentOutcomes) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("asOf", screening.baseDate().toString());
    root.put("horizonDays", properties.getHorizonDays());

    Map<String, Object> m = new LinkedHashMap<>();
    List<Map<String, Object>> indices = new ArrayList<>();
    for (MarketFeatures.IndexFeature i : market.indices()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", i.code());
      row.put("name", i.name());
      row.put("close", round(i.close(), 2));
      row.put("r1", round(i.r1()));
      row.put("r5", round(i.r5()));
      row.put("r20", round(i.r20()));
      row.put("r60", round(i.r60()));
      row.put("distMa20", round(i.distMa20()));
      row.put("distMa60", round(i.distMa60()));
      indices.add(row);
    }
    m.put("index", indices);
    List<Map<String, Object>> flows = new ArrayList<>();
    for (MarketFeatures.FlowFeature f : market.flows()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("mkt", f.market());
      row.put("frgn1", f.frgn1());
      row.put("inst1", f.inst1());
      row.put("indi1", f.indi1());
      row.put("frgn5", f.frgn5());
      row.put("inst5", f.inst5());
      row.put("indi5", f.indi5());
      flows.add(row);
    }
    m.put("flow", flows);
    List<Map<String, Object>> global = new ArrayList<>();
    for (MarketFeatures.GlobalFeature g : market.global()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sym", g.symbol());
      row.put("date", g.date().toString());
      row.put("close", round(g.close(), 2));
      row.put("r1", round(g.r1()));
      row.put("r5", round(g.r5()));
      row.put("r20", round(g.r20()));
      row.put("r60", round(g.r60()));
      global.add(row);
    }
    m.put("global", global);
    if (market.links() != null && !market.links().isEmpty()) {
      List<Map<String, Object>> links = new ArrayList<>();
      for (GlobalLink l : market.links()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kr", l.krIndex());
        row.put("us", l.usSymbol());
        row.put("beta", round(l.beta(), 3));
        row.put("corr", round(l.corr(), 3));
        row.put("n", l.n());
        links.add(row);
      }
      m.put("link", links);
    }
    Map<String, Object> sigma = new LinkedHashMap<>();
    market.sigma5d().forEach((k, v) -> sigma.put(k, round(v)));
    m.put("sigma5d", sigma);
    if (market.trends() != null && !market.trends().isEmpty()) {
      m.put("trend", market.trends().stream().map(AdvicePromptBuilder::trendRow).toList());
    }
    root.put("market", m);

    // 관측 기준일·적용 구간·품질 — 새 루트 키는 market 뒤에 둔다 (asOf·horizonDays·market 순서에 기대는 소비자가 있다)
    root.put("dataAsOf", market.dataAsOf());
    if (market.entryDate() != null && market.exitDate() != null) {
      Map<String, Object> window = new LinkedHashMap<>();
      window.put("entry", market.entryDate().toString());
      window.put("exit", market.exitDate().toString());
      window.put("entryRule", "다음 영업일 시가");
      window.put("exitRule", properties.getHorizonDays() + "번째 영업일 종가");
      root.put("window", window);
    }
    root.put("dataQuality", (quality == null ? DataQuality.OK : quality).getCode());

    Map<String, Object> sectors = new LinkedHashMap<>();
    sectors.put("columns", SECTOR_COLUMNS);
    sectors.put("top", market.topSectors().stream().map(AdvicePromptBuilder::sectorRow).toList());
    sectors.put("bottom", market.bottomSectors().stream().map(AdvicePromptBuilder::sectorRow).toList());
    root.put("sectors", sectors);

    Map<String, Object> cand = new LinkedHashMap<>();
    cand.put("columns", CANDIDATE_COLUMNS);
    cand.put("rows", candidates.stream().map(AdvicePromptBuilder::candidateRow).toList());
    root.put("candidates", cand);

    if (news != null && news.size() > 0) {
      root.put("news", newsBlock(news));
    }
    if (scoreboard != null && !scoreboard.isEmpty()) {
      root.put("scoreboard", scoreboard);
    }
    // note-v1: 확정 빈도표는 scoreboard(느린 층 실적) 뒤·lessons(규칙) 앞 — 둘 사이의 "관찰 표" 자리
    if (recentOutcomes != null && !recentOutcomes.isEmpty()) {
      root.put("recentOutcomes", recentOutcomes);
    }
    if (lessons != null && !lessons.isEmpty()) {
      List<Map<String, Object>> list = new ArrayList<>();
      for (LessonRow l : lessons) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", l.lessonId());
        row.put("condition", l.condition());
        row.put("text", l.lessonText());
        list.add(row);
      }
      root.put("lessons", list);
    }
    Map<String, Object> w = new LinkedHashMap<>();
    weights.forEach((k, v) -> w.put(k, round(v)));
    root.put("weights", w);
    return root;
  }

  /**
   * 규칙 추세 1행: 라벨·점수·성분·지속·이동평균·breadth·기저율. 라벨은 확정 사실이라 LLM 이 바꿀 수 없음을 프롬프트가 명시한다.
   */
  private static Map<String, Object> trendRow(MarketTrend t) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("index", t.indexCode());
    row.put("code", t.code().getCode());
    row.put("score", t.score());
    row.put("components", t.components());
    row.put("since", str(t.since()));
    row.put("days", t.days());
    row.put("close", round(t.close(), 2));
    row.put("ma20", round(t.ma20(), 2));
    row.put("ma60", round(t.ma60(), 2));
    row.put("ma120", round(t.ma120(), 2));
    row.put("breadth", round(t.breadth()));
    if (t.base() != null) {
      Map<String, Object> base = new LinkedHashMap<>();
      base.put("episodes", t.base().episodes());
      base.put("medianDays", round(t.base().medianDays(), 1));
      base.put("fwd5", forward(t.base().fwd5()));
      base.put("fwd20", forward(t.base().fwd20()));
      row.put("base", base);
    }
    return row;
  }

  private static Map<String, Object> forward(MarketTrend.Forward f) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (f == null) {
      return m;
    }
    m.put("n", f.n());
    m.put("pUp", round(f.pUp()));
    m.put("mean", round(f.mean()));
    return m;
  }

  private static String str(java.time.LocalDate date) {
    return date == null ? null : date.toString();
  }

  /**
   * 섹터 1행(SECTOR_COLUMNS 순). cw5 는 %p, rs·mom 은 소수. consistent/overheated 는 불리언 그대로(null 이면 위치 보존을 위해 null).
   */
  private static List<Object> sectorRow(MarketFeatures.SectorFeature s) {
    List<Object> row = new ArrayList<>();
    row.add(s.code());
    row.add(s.name());
    row.add(round(s.cw5d()));
    row.add(round(s.rising()));
    row.add(round(s.nearHigh()));
    row.add(s.frgn5());
    row.add(s.members());
    row.add(round(s.rs5()));
    row.add(round(s.rs20()));
    row.add(round(s.rs60()));
    row.add(round(s.mom()));
    row.add(s.consistent());
    row.add(s.overheated());
    return row;
  }

  /**
   * 후보 1행(CANDIDATE_COLUMNS 순). secRs60·secCons(advice-v6) 는 후보 features 의 secRs5/20/60(스크리닝 feat CTE) 에서 온다.
   */
  private static List<Object> candidateRow(CandidateRow c) {
    Map<String, Object> f = c.features();
    List<Object> row = new ArrayList<>();
    row.add(c.ticker());
    row.add(c.stockName());
    row.add(c.sectorCode());
    row.add(round(c.quantScore()));
    row.add(round(num(f.get("r20"))));
    row.add(round(num(f.get("r60"))));
    row.add(round(num(f.get("distHigh52w"))));
    row.add(round(num(f.get("tvRatio"))));
    row.add(round(c.signals().containsKey("FOREIGN_FLOW") ? c.signals().get("FOREIGN_FLOW").raw() : null));
    row.add(round(c.signals().containsKey("INST_FLOW") ? c.signals().get("INST_FLOW").raw() : null));
    row.add(round(c.signals().containsKey("RS_INDEX") ? c.signals().get("RS_INDEX").raw() : null));
    row.add(round(num(f.get("per")), 2));
    row.add(round(num(f.get("pbr")), 2));
    row.add(round(num(f.get("vol20d"))));
    row.add(round(num(f.get("secRs60"))));
    row.add(secCons(f));
    return row;
  }

  /**
   * 후보 소속 섹터의 세 구간 연속 초과(advice-v6): secRs5·secRs20·secRs60 이 모두 있고 전부 > 0 이면 1, 모두 있지만 하나라도 ≤ 0 이면 0,
   * 하나라도 없으면(업종 지수 없음·창 부족) null. 가드의 확신 클램프(AdviceGuard)도 같은 정의를 쓴다.
   */
  static Integer secCons(Map<String, Object> features) {
    if (features == null) {
      return null;
    }
    Double rs5 = num(features.get("secRs5"));
    Double rs20 = num(features.get("secRs20"));
    Double rs60 = num(features.get("secRs60"));
    if (rs5 == null || rs20 == null || rs60 == null) {
      return null;
    }
    return rs5 > 0 && rs20 > 0 && rs60 > 0 ? 1 : 0;
  }

  private static Double num(Object value) {
    return value instanceof Number n ? n.doubleValue() : null;
  }

  static Double round(Double value) {
    return round(value, 4);
  }

  static Double round(Double value, int digits) {
    if (value == null || value.isNaN() || value.isInfinite()) {
      return null;
    }
    double scale = Math.pow(10, digits);
    return Math.round(value * scale) / scale;
  }
}
