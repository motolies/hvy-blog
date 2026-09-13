package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.service.GlobalLinkService;
import kr.hvy.blog.modules.advisor.application.service.MarketFeatureService;
import kr.hvy.blog.modules.advisor.application.service.MarketTrendService;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketTrend;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 시장 도구 3종 — 시장 개요·규칙 추세·미국 연동(β/상관). 전부 기존 서비스(MarketFeatureService·MarketTrendService·GlobalLinkService) 재사용.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MarketToolkit {

  static final String AS_OF_DESC = "기준 거래일, yyyy-MM-dd. 생략하면 마지막 거래일. 미래 날짜는 마지막 거래일로 조정된다";

  private final ToolSupport support;
  private final MarketFeatureService marketFeatures;
  private final MarketTrendService marketTrends;
  private final GlobalLinkService globalLinks;
  private final AdvisorProperties advisor;

  @Tool(name = "marketOverview", description = "시장 개요를 한 번에 가져온다: KOSPI(0001)·KOSDAQ(1001)·KOSPI200(2001) 종가와 1/5/20/60일 수익률·MA 이격, "
      + "시장 수급(외국인·기관·개인 1일/5일, 억원), 미국 지수(SPX·COMP·SOX·.DJI)와 원달러(FX@KRW)의 수익률, 주도/부진 섹터, 5일 변동성, 규칙 기반 추세 라벨, "
      + "β·상관, 데이터 기준일. 시장 전반·지수·수급·섹터·해외 관련 질문의 기본 도구다.")
  public Map<String, Object> marketOverview(@ToolParam(required = false, description = AS_OF_DESC) String asOf, ToolContext context) {
    return support.run("marketOverview", context, () -> {
      Optional<LocalDate> date = support.asOf(asOf);
      if (date.isEmpty()) {
        return ToolJson.noData(null);
      }
      MarketFeatures f = marketFeatures.features(date.get());
      if (f.indices().isEmpty()) {
        return ToolJson.noData(date.get());
      }
      support.noteAsOf(context, f.asOf());
      Map<String, Object> m = ToolJson.obj();
      m.put("asOf", f.asOf().toString());
      m.put("indices", f.indices().stream().map(i -> {
        Map<String, Object> x = ToolJson.obj();
        x.put("code", i.code());
        ToolJson.put(x, "name", i.name());
        x.put("close", ToolJson.r4(i.close()));
        ToolJson.put(x, "r1", ToolJson.r4(i.r1()));
        ToolJson.put(x, "r5", ToolJson.r4(i.r5()));
        ToolJson.put(x, "r20", ToolJson.r4(i.r20()));
        ToolJson.put(x, "r60", ToolJson.r4(i.r60()));
        ToolJson.put(x, "distMa20", ToolJson.r4(i.distMa20()));
        ToolJson.put(x, "distMa60", ToolJson.r4(i.distMa60()));
        return x;
      }).toList());
      m.put("flowUnit", "억원");
      m.put("flows", f.flows().stream().map(fl -> {
        Map<String, Object> x = ToolJson.obj();
        x.put("market", fl.market());
        ToolJson.put(x, "frgn1", eok(fl.frgn1()));
        ToolJson.put(x, "inst1", eok(fl.inst1()));
        ToolJson.put(x, "indi1", eok(fl.indi1()));
        ToolJson.put(x, "frgn5", eok(fl.frgn5()));
        ToolJson.put(x, "inst5", eok(fl.inst5()));
        ToolJson.put(x, "indi5", eok(fl.indi5()));
        return x;
      }).toList());
      m.put("global", f.global().stream().map(g -> {
        Map<String, Object> x = ToolJson.obj();
        x.put("symbol", g.symbol());
        ToolJson.put(x, "date", g.date() == null ? null : g.date().toString());
        x.put("close", ToolJson.r4(g.close()));
        ToolJson.put(x, "r1", ToolJson.r4(g.r1()));
        ToolJson.put(x, "r5", ToolJson.r4(g.r5()));
        ToolJson.put(x, "r20", ToolJson.r4(g.r20()));
        ToolJson.put(x, "r60", ToolJson.r4(g.r60()));
        return x;
      }).toList());
      m.put("sectorsTop", sectors(f.topSectors()));
      m.put("sectorsBottom", sectors(f.bottomSectors()));
      ToolJson.put(m, "sigma5d", f.sigma5d() == null || f.sigma5d().isEmpty() ? null : f.sigma5d());
      m.put("trend", f.trends().stream().map(MarketToolkit::trendBrief).toList());
      m.put("links", f.links().stream().map(MarketToolkit::link).toList());
      Map<String, Object> window = ToolJson.obj();
      ToolJson.put(window, "entry", f.entryDate() == null ? null : f.entryDate().toString());
      ToolJson.put(window, "exit", f.exitDate() == null ? null : f.exitDate().toString());
      m.put("window", window);
      m.put("dataAsOf", f.dataAsOf());
      m.put("note", "수익률·이격·σ는 비율(0.012 = 1.2%). 미국 지수는 국내 기준일 직전 현지 마감(T-1). 섹터 지표는 KOSPI·KOSDAQ 양시장 기준");
      return m;
    });
  }

  @Tool(name = "marketTrend", description = "KOSPI(0001)·KOSDAQ(1001)의 규칙 기반 중기 추세 라벨(BULL 강세 / SIDEWAYS 보합 / BEAR 약세)과 성분 5개 점수(ma20, ma60, ma120, "
      + "ret60, breadth), 전환 이후 경과 거래일, 기저율(같은 라벨에서 전방 5일·20일 상승 확률과 평균 수익률, 에피소드 평균 길이)을 돌려준다. "
      + "'강세장이야?', '추세가 얼마나 갈까?' 류 질문에 쓴다. 라벨은 LLM 이 아니라 규칙이 정한다.")
  public Map<String, Object> marketTrend(@ToolParam(required = false, description = AS_OF_DESC) String asOf, ToolContext context) {
    return support.run("marketTrend", context, () -> {
      Optional<LocalDate> date = support.asOf(asOf);
      if (date.isEmpty()) {
        return ToolJson.noData(null);
      }
      List<MarketTrend> trends = marketTrends.trends(date.get());
      if (trends.isEmpty()) {
        return ToolJson.noData(date.get());
      }
      support.noteAsOf(context, date.get());
      Map<String, Object> m = ToolJson.obj();
      m.put("asOf", date.get().toString());
      AdvisorProperties.Trend cfg = advisor.getTrend();
      m.put("rule", String.format("성분 합 ≥%d BULL, ≤%d BEAR, 그 사이 SIDEWAYS. 전환은 %d거래일 연속 확인 후", cfg.getBullThreshold(), cfg.getBearThreshold(), cfg.getConfirmDays()));
      m.put("items", trends.stream().map(t -> {
        Map<String, Object> x = trendBrief(t);
        ToolJson.put(x, "rawCode", t.rawCode() == null ? null : t.rawCode().getCode());
        ToolJson.put(x, "components", t.components());
        ToolJson.put(x, "close", ToolJson.r4(t.close()));
        ToolJson.put(x, "ma20", ToolJson.r4(t.ma20()));
        ToolJson.put(x, "ma60", ToolJson.r4(t.ma60()));
        ToolJson.put(x, "ma120", ToolJson.r4(t.ma120()));
        ToolJson.put(x, "breadthAboveMa20", ToolJson.r4(t.breadth()));
        if (t.base() != null) {
          Map<String, Object> b = ToolJson.obj();
          b.put("episodes", t.base().episodes());
          ToolJson.put(b, "medianDays", ToolJson.r4(t.base().medianDays()));
          ToolJson.put(b, "fwd5", forward(t.base().fwd5()));
          ToolJson.put(b, "fwd20", forward(t.base().fwd20()));
          x.put("base", b);
        }
        return x;
      }).toList());
      return m;
    });
  }

  @Tool(name = "globalLink", description = "국내 지수와 미국 지수의 연동 강도(β·상관·표본 수 n)를 돌려준다. krIndex 와 usSymbol 을 모두 주면 그 쌍, 생략하면 설정된 쌍 전부"
      + "(KOSPI:SPX, KOSPI:SOX, KOSDAQ:COMP, KOSDAQ:SOX). n 이 작으면 추정이 불안정하다. '나스닥 떨어지면 코스닥은?' 류 질문에 쓴다.")
  public Map<String, Object> globalLink(
      @ToolParam(required = false, description = "국내 지수: KOSPI 또는 0001, KOSDAQ 또는 1001") String krIndex,
      @ToolParam(required = false, description = "미국 심볼: SPX, COMP, SOX, .DJI") String usSymbol,
      @ToolParam(required = false, description = AS_OF_DESC) String asOf, ToolContext context) {
    return support.run("globalLink", context, () -> {
      Optional<LocalDate> date = support.asOf(asOf);
      if (date.isEmpty()) {
        return ToolJson.noData(null);
      }
      List<GlobalLink> items;
      String kr = normalizeIndex(krIndex);
      String us = usSymbol == null || usSymbol.isBlank() ? null : usSymbol.trim().toUpperCase();
      if (kr != null && us != null) {
        items = List.of(globalLinks.link(kr, us, date.get()));
      } else {
        items = globalLinks.links(date.get());
      }
      if (items.isEmpty() || items.stream().allMatch(l -> l.n() == 0)) {
        return ToolJson.noData(date.get());
      }
      support.noteAsOf(context, date.get());
      Map<String, Object> m = ToolJson.obj();
      m.put("asOf", date.get().toString());
      m.put("windowTradingDays", advisor.getMorning().getLinkWindowDays());
      m.put("items", items.stream().map(MarketToolkit::link).toList());
      m.put("note", "β = 미국 1일 수익률에 대한 국내 1일 수익률 회귀 기울기(미국 현지일 ∈ [국내 직전 거래일, d-1] 쌍짓기)");
      return m;
    });
  }

  /**
   * KOSPI/KOSDAQ 이름 또는 코드 → 지수 코드. 모르면 null.
   */
  static String normalizeIndex(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String v = value.trim().toUpperCase();
    return switch (v) {
      case "KOSPI", "0001", "코스피" -> "0001";
      case "KOSDAQ", "1001", "코스닥" -> "1001";
      default -> v;
    };
  }

  private static List<Map<String, Object>> sectors(List<MarketFeatures.SectorFeature> list) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (MarketFeatures.SectorFeature s : list) {
      Map<String, Object> x = ToolJson.obj();
      x.put("code", s.code());
      ToolJson.put(x, "name", s.name());
      ToolJson.put(x, "cw5d", ToolJson.r4(s.cw5d()));
      ToolJson.put(x, "rising", ToolJson.r4(s.rising()));
      ToolJson.put(x, "nearHigh", ToolJson.r4(s.nearHigh()));
      ToolJson.put(x, "frgn5", eok(s.frgn5()));
      x.put("members", s.members());
      out.add(x);
    }
    return out;
  }

  private static Map<String, Object> trendBrief(MarketTrend t) {
    Map<String, Object> x = ToolJson.obj();
    x.put("index", t.indexCode());
    ToolJson.put(x, "code", t.code() == null ? null : t.code().getCode());
    x.put("score", t.score());
    ToolJson.put(x, "since", t.since() == null ? null : t.since().toString());
    x.put("days", t.days());
    return x;
  }

  private static Map<String, Object> forward(MarketTrend.Forward f) {
    if (f == null) {
      return null;
    }
    Map<String, Object> x = ToolJson.obj();
    x.put("n", f.n());
    ToolJson.put(x, "pUp", ToolJson.r4(f.pUp()));
    ToolJson.put(x, "mean", ToolJson.r4(f.mean()));
    return x;
  }

  private static Map<String, Object> link(GlobalLink l) {
    Map<String, Object> x = ToolJson.obj();
    x.put("kr", l.krIndex());
    x.put("us", l.usSymbol());
    ToolJson.put(x, "beta", ToolJson.r4(l.beta()));
    ToolJson.put(x, "corr", ToolJson.r4(l.corr()));
    x.put("n", l.n());
    return x;
  }

  /**
   * 원 → 억원 정수.
   */
  static Long eok(Long won) {
    return won == null ? null : Math.round(won / 1e8);
  }
}
