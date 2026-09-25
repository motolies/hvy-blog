package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.chat.AdhocAdviceRequester;
import kr.hvy.blog.modules.advisor.application.service.AdviceComparison;
import kr.hvy.blog.modules.advisor.application.service.AdvisorKpiService;
import kr.hvy.blog.modules.advisor.application.service.CandidateScreeningService;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import kr.hvy.blog.modules.advisor.domain.model.TrendOutlook;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.ScoreWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 판단 도구 8종 — 종류별 최신/특정일 판단+픽, 호라이즌별 픽, 저녁·아침 비교, 아침·장중 점검과 채점, 정량 스크리닝 상위, 성과 요약(전부 읽기),
 * 그리고 유일한 쓰기 도구 수시 판단 요청(requestAdvice, chat-v2). 전부 advisor 저장소·서비스 재사용.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdviceToolkit {

  static final int SCREEN_CAP = 20;
  static final int MAX_WEEKS = 52;
  static final int DEFAULT_WEEKS = 8;

  private final ToolSupport support;
  private final AdviceWriter adviceWriter;
  private final ScoreWriter scoreWriter;
  private final MorningCheckWriter morningChecks;
  private final IntradayCheckWriter intradayChecks;
  private final CandidateScreeningService screening;
  private final WeightSetRepository weightSets;
  private final AdvisorKpiService kpi;
  private final AdvisorProperties advisor;
  private final AdhocAdviceRequester adhocRequester;

  /** kind 파라미터 허용값 (오류 안내용) */
  static final String KIND_CODES = Arrays.stream(AdviceKind.values()).map(AdviceKind::getCode).collect(Collectors.joining("|"));

  @Tool(name = "latestAdvice", description = "봇이 발행한 판단(LIVE): 시장 국면(RISK_ON/NEUTRAL/RISK_OFF)과 KOSPI·KOSDAQ 방향·확신, 근거, 추세 라벨과 지속 전망, 주도 섹터, "
      + "적용 구간(진입·청산일), 규칙 합성 국면(ruleRegime: 추세×변동성·정책 표 한도·테마 강약), 종목 픽(순위·코드·이름·LONG/AVOID·확신·thesis 근거·risk 리스크). kind 로 종류를 고른다: DAILY(19:30 일일, 기본)·MORNING(아침 재판정)·"
      + "H20/H60/H180(장기 호라이즌)·ADHOC(채팅 요청 수시 판단). baseDate 를 주면 그날 또는 그 이전 마지막 판단. "
      + "'오늘 판단 근거 설명해줘', '왜 이 종목을 골랐어', '아직 유효해?' 에 쓰고 thesis·risk 원문을 인용한다(새로 짓지 않는다). 해당 종류가 없으면 no_data 다.")
  public Map<String, Object> latestAdvice(@ToolParam(required = false, description = "판단 기준일 yyyy-MM-dd. 생략하면 가장 최근") String baseDate,
      @ToolParam(required = false, description = "판단 종류 DAILY|MORNING|H20|H60|H180|ADHOC. 생략하면 DAILY") String kind, ToolContext context) {
    return support.run("latestAdvice", context, () -> {
      Optional<AdviceKind> parsed = parseKind(kind);
      if (parsed.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_BAD_ARGUMENT, "kind 는 " + KIND_CODES + " 중 하나", null);
      }
      return adviceBody(parsed.get(), baseDate, context);
    });
  }

  @Tool(name = "horizonPicks", description = "호라이즌(보유 거래일)별 최신 판단과 픽: h=5 는 19:30 일일 판단(DAILY), 20·60·180 은 장기 호라이즌 판단(H20·H60·H180). "
      + "'한 달(20일) 보면 뭐가 좋아?', '장기 추천은?' 에 쓴다. 해당 호라이즌 판단이 아직 발행 전이면 no_data 이고, 그때는 없다고 답한다(일일 판단으로 대신 답하지 않는다).")
  public Map<String, Object> horizonPicks(@ToolParam(description = "호라이즌 거래일 5|20|60|180") Integer h,
      @ToolParam(required = false, description = "판단 기준일 yyyy-MM-dd. 생략하면 가장 최근") String baseDate, ToolContext context) {
    return support.run("horizonPicks", context, () -> {
      Optional<AdviceKind> kind = kindOfHorizon(h);
      if (kind.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_BAD_ARGUMENT, "h 는 5·20·60·180 중 하나", null);
      }
      return adviceBody(kind.get(), baseDate, context);
    });
  }

  @Tool(name = "compareAdvice", description = "같은 기준일의 저녁 일일 판단(DAILY, 19:30)과 아침 재판정(MORNING)을 비교한다: 종목마다 KEEP(유지)·ADD(아침 추가)·DROP(아침 제외)과 사유, "
      + "방향·확신 변화. '어제 저녁이랑 비교해줘', '아침에 뭐가 바뀌었어?' 에 쓴다. 아침 재판정이 없으면 no_data 이고, 그때는 없다고 답한다.")
  public Map<String, Object> compareAdvice(@ToolParam(required = false, description = "저녁 판단 기준일 yyyy-MM-dd. 생략하면 가장 최근 일일 판단") String baseDate,
      ToolContext context) {
    return support.run("compareAdvice", context, () -> {
      Optional<AdviceHeader> evening = resolve(AdviceKind.DAILY, baseDate);
      if (evening.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_NO_DATA, "비교할 일일 판단이 없다", null);
      }
      AdviceHeader e = evening.get();
      support.noteAsOf(context, e.baseDate());
      Optional<AdviceHeader> morning = adviceWriter.find(e.baseDate(), AdviceKind.MORNING, AdviceVariant.LIVE);
      if (morning.isEmpty()) {
        Map<String, Object> none = ToolJson.error(ToolJson.ERROR_NO_DATA, e.baseDate() + " 기준 아침 재판정(MORNING)이 아직 없다 — 비교 불가", e.baseDate());
        none.put("eveningAdviceId", e.adviceId());
        return none;
      }
      return comparison(e, morning.get());
    });
  }

  @Tool(name = "requestAdvice", description = "새 수시 판단(ADHOC)을 지금 생성하도록 요청한다 — '추천 새로 뽑아줘', '지금 기준으로 판단 다시 해줘' 처럼 생성을 요청할 때만 쓴다. "
      + "19:30 일일 판단과 같은 입력·규칙으로 비동기 실행되고 즉시 runId 를 돌려준다(결과는 1~3분 뒤 #hvy-advisor 발행, 이후 latestAdvice(kind=ADHOC) 로 조회). "
      + "허용 사용자만, 하루 상한이 있다. 같은 기준일 수시 판단이 이미 있으면 새로 만들지 않고 그 id 를 돌려준다. 수시 판단은 성과 집계에서 제외된다.")
  public Map<String, Object> requestAdvice(ToolContext context) {
    return support.runWithoutTransaction("requestAdvice", context, () -> {
      String userId = ChatRequestScope.from(context).flatMap(ChatRequestScope::userId).orElse(null);
      return requested(adhocRequester.request(userId));
    });
  }

  /**
   * 판단 1건의 본문(latestAdvice·horizonPicks 공용). 없으면 종류에 맞는 no_data — DAILY 가 아닌 종류는 "아직 발행 전" 을 분명히 적어 모델이 다른 종류로 대신 답하지 않게 한다.
   */
  private Map<String, Object> adviceBody(AdviceKind kind, String baseDate, ToolContext context) {
    Optional<AdviceHeader> found = resolve(kind, baseDate);
    if (found.isEmpty()) {
      return ToolJson.error(ToolJson.ERROR_NO_DATA, kind == AdviceKind.DAILY ? "발행된 일일 판단이 없다"
          : kind.getCode() + "(" + kind.getDesc() + ") 판단은 아직 발행 전이다 — 저장된 판단이 없다", null);
    }
    AdviceHeader h = found.get();
    support.noteAsOf(context, h.baseDate());
    Map<String, CandidateRow> candidates = new HashMap<>();
    for (CandidateRow c : adviceWriter.candidates(h.adviceId())) {
      candidates.put(c.ticker(), c);
    }
    List<PickRow> picks = adviceWriter.picks(h.adviceId());
    Map<String, Object> m = ToolJson.obj();
    m.put("adviceId", h.adviceId());
    m.put("kind", h.adviceKind() == null ? AdviceKind.DAILY.getCode() : h.adviceKind().getCode());
    m.put("baseDate", h.baseDate().toString());
    m.put("horizonDays", h.horizonDays());
    ToolJson.put(m, "regime", h.regimeCode() == null ? null : h.regimeCode().getCode());
    ToolJson.put(m, "kospiDir", h.kospiDir() == null ? null : h.kospiDir().getCode());
    ToolJson.put(m, "kosdaqDir", h.kosdaqDir() == null ? null : h.kosdaqDir().getCode());
    ToolJson.put(m, "pUp", ToolJson.r4(h.pUp()));
    ToolJson.put(m, "rationale", h.regimeRationale());
    Map<String, Object> trend = ToolJson.obj();
    ToolJson.put(trend, "kospi", h.trendKospi() == null ? null : h.trendKospi().getCode());
    ToolJson.put(trend, "kosdaq", h.trendKosdaq() == null ? null : h.trendKosdaq().getCode());
    ToolJson.put(m, "trend", trend.isEmpty() ? null : trend);
    // M6: 규칙 합성 국면·정책 한도·테마 강약 (저장 시점 스냅샷, M6 이전·MORNING 판단은 없음)
    ToolJson.put(m, "ruleRegime", h.regime() == null ? null : MarketToolkit.regimeJson(h.regime()));
    if (h.outlooks() != null && !h.outlooks().isEmpty()) {
      List<Map<String, Object>> outlooks = new ArrayList<>();
      for (TrendOutlook o : h.outlooks()) {
        Map<String, Object> x = ToolJson.obj();
        x.put("index", o.indexCode());
        ToolJson.put(x, "persist", o.persist() == null ? null : o.persist().getCode());
        x.put("confidence", ToolJson.r4(o.confidence()));
        ToolJson.put(x, "invalidation", o.invalidation() == null ? null : o.invalidation().getCode());
        outlooks.add(x);
      }
      m.put("trendOutlook", outlooks);
    }
    if (h.leadingSectors() != null && !h.leadingSectors().isEmpty()) {
      List<Map<String, Object>> sectors = new ArrayList<>();
      for (SectorCall s : h.leadingSectors()) {
        Map<String, Object> x = ToolJson.obj();
        x.put("code", s.code());
        ToolJson.put(x, "name", s.name());
        ToolJson.put(x, "reason", s.reason());
        sectors.add(x);
      }
      m.put("leadingSectors", sectors);
    }
    Map<String, Object> window = ToolJson.obj();
    ToolJson.put(window, "entry", h.entryDate() == null ? null : h.entryDate().toString());
    ToolJson.put(window, "exit", h.exitDate() == null ? null : h.exitDate().toString());
    ToolJson.put(m, "window", window.isEmpty() ? null : window);
    ToolJson.put(m, "dataAsOf", h.dataAsOf() == null || h.dataAsOf().isEmpty() ? null : h.dataAsOf());
    ToolJson.put(m, "dataQuality", h.dataQuality() == null ? null : h.dataQuality().getCode());
    ToolJson.put(m, "summary", h.summary());
    ToolJson.put(m, "model", h.model());
    ToolJson.put(m, "promptVersion", h.promptVersion());
    m.put("candidates", candidates.size());
    List<Map<String, Object>> pickList = new ArrayList<>();
    for (PickRow p : picks) {
      CandidateRow c = candidates.get(p.ticker());
      Map<String, Object> x = ToolJson.obj();
      x.put("rank", p.pickRank());
      x.put("tk", p.ticker());
      ToolJson.put(x, "nm", c == null ? null : c.stockName());
      ToolJson.put(x, "sector", c == null ? null : c.sectorName());
      ToolJson.put(x, "dir", p.direction() == null ? null : p.direction().getCode());
      x.put("conv", ToolJson.r4(p.conviction()));
      ToolJson.put(x, "thesis", p.thesis());
      ToolJson.put(x, "risk", p.riskNote());
      ToolJson.put(x, "quantRank", c == null ? null : c.quantRank());
      pickList.add(x);
    }
    m.put("picks", pickList);
    ToolJson.put(m, "publishedAt", h.publishedAt() == null ? null : h.publishedAt().toString());
    m.put("note", "픽 후보는 " + advisor.getMarkets() + " 중 " + advisor.getPickUniverse().getDesc() + "(현재 설정), 국면·추세는 양시장. 이 판단은 투자 자문이 아니라 개인 실험"
        + (h.adviceKind() == AdviceKind.ADHOC ? ". 채팅 요청 수시 판단 — 성과 집계 제외" : ""));
    return m;
  }

  /**
   * 저녁·아침 비교 결과 맵. 조치가 선언값(M4 이후 아침 판단이 기록)인지 계산값(코드 집합 차이)인지 함께 싣는다.
   */
  private Map<String, Object> comparison(AdviceHeader evening, AdviceHeader morning) {
    Map<String, CandidateRow> names = new HashMap<>();
    for (CandidateRow c : adviceWriter.candidates(evening.adviceId())) {
      names.put(c.ticker(), c);
    }
    for (CandidateRow c : adviceWriter.candidates(morning.adviceId())) {
      names.putIfAbsent(c.ticker(), c);
    }
    List<AdviceComparison.Change> changes = AdviceComparison.compare(adviceWriter.picks(evening.adviceId()), adviceWriter.picks(morning.adviceId()),
        declaredDrops(morning));
    Map<String, Object> m = ToolJson.obj();
    m.put("baseDate", evening.baseDate().toString());
    m.put("eveningAdviceId", evening.adviceId());
    m.put("morningAdviceId", morning.adviceId());
    Map<String, Object> counts = ToolJson.obj();
    for (PickAction action : PickAction.values()) {
      counts.put(action.getCode(), changes.stream().filter(c -> c.action() == action).count());
    }
    m.put("counts", counts);
    List<Map<String, Object>> items = new ArrayList<>();
    for (AdviceComparison.Change c : changes) {
      Map<String, Object> x = ToolJson.obj();
      x.put("tk", c.ticker());
      CandidateRow row = names.get(c.ticker());
      ToolJson.put(x, "nm", row == null ? null : row.stockName());
      x.put("action", c.action().getCode());
      ToolJson.put(x, "reason", c.reason());
      ToolJson.put(x, "evening", pickBrief(c.evening()));
      ToolJson.put(x, "morning", pickBrief(c.morning()));
      items.add(x);
    }
    m.put("changes", items);
    boolean declared = changes.stream().anyMatch(AdviceComparison.Change::declared);
    m.put("declared", declared);
    m.put("note", declared ? "action·reason 은 아침 재판정이 기록한 원문" : "action 은 저녁·아침 픽 코드 집합 차이로 계산한 값 — DROP·KEEP 사유는 모델 원문이 아니다");
    return m;
  }

  /**
   * 아침 판단 diff_json.drop 의 티커 → 사유 (M4). DROP 은 아침 픽에 행이 없어 여기가 모델 원문 사유의 유일한 출처다. 없거나 형식이 다르면 빈 맵.
   */
  static Map<String, String> declaredDrops(AdviceHeader morning) {
    Map<String, String> drops = new HashMap<>();
    if (morning.diffJson() == null || !(morning.diffJson().get("drop") instanceof List<?> list)) {
      return drops;
    }
    for (Object item : list) {
      if (item instanceof Map<?, ?> m && m.get("ticker") instanceof String ticker) {
        Object reason = m.get("reason");
        drops.put(ticker, reason == null ? null : reason.toString());
      }
    }
    return drops;
  }

  private static Map<String, Object> pickBrief(PickRow p) {
    if (p == null) {
      return null;
    }
    Map<String, Object> x = ToolJson.obj();
    x.put("rank", p.pickRank());
    ToolJson.put(x, "dir", p.direction() == null ? null : p.direction().getCode());
    x.put("conv", ToolJson.r4(p.conviction()));
    return x;
  }

  /**
   * 수시 판단 요청 결과를 도구 응답으로. 거부는 error 코드, 진행·존재는 status 로 돌려주고 모델이 결과를 지어내지 않게 다음 행동을 note 로 적는다.
   */
  static Map<String, Object> requested(AdhocAdviceRequester.Outcome o) {
    LocalDate d = o.baseDate();
    return switch (o.status()) {
      case FORBIDDEN -> ToolJson.error(ToolJson.ERROR_FORBIDDEN, o.message(), null);
      case LIMIT -> ToolJson.error(ToolJson.ERROR_LIMIT, o.message(), d);
      case NOT_READY -> ToolJson.error(ToolJson.ERROR_NO_DATA, o.message(), d);
      case REJECTED -> ToolJson.error(ToolJson.ERROR_INTERNAL, o.message(), d);
      case STARTED, EXISTS, RUNNING -> {
        Map<String, Object> m = ToolJson.obj();
        m.put("status", o.status().getCode());
        m.put("kind", AdviceKind.ADHOC.getCode());
        ToolJson.put(m, "runId", o.runId());
        ToolJson.put(m, "adviceId", o.adviceId());
        ToolJson.put(m, "baseDate", d == null ? null : d.toString());
        ToolJson.put(m, "message", o.message());
        m.put("note", o.status() == AdhocAdviceRequester.Status.EXISTS
            ? "latestAdvice(kind=ADHOC, baseDate=" + d + ") 로 내용을 조회해 답할 것"
            : "비동기 실행 중 — 결과는 1~3분 뒤 #hvy-advisor 에 '수시 판단' 으로 발행되고 latestAdvice(kind=ADHOC) 로 조회된다. 지금 결과를 지어내지 말 것");
        yield m;
      }
    };
  }

  /**
   * kind 파라미터 해석(대소문자 무시). 비었으면 DAILY, 모르는 값이면 빈 Optional.
   */
  static Optional<AdviceKind> parseKind(String kind) {
    if (kind == null || kind.isBlank()) {
      return Optional.of(AdviceKind.DAILY);
    }
    String code = kind.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(AdviceKind.values()).filter(k -> k.getCode().equals(code)).findFirst();
  }

  /**
   * 호라이즌(거래일) → 판단 종류. 5 는 19:30 일일 판단이다.
   */
  static Optional<AdviceKind> kindOfHorizon(Integer h) {
    if (h == null) {
      return Optional.empty();
    }
    return switch (h) {
      case 5 -> Optional.of(AdviceKind.DAILY);
      case 20 -> Optional.of(AdviceKind.H20);
      case 60 -> Optional.of(AdviceKind.H60);
      case 180 -> Optional.of(AdviceKind.H180);
      default -> Optional.empty();
    };
  }

  @Tool(name = "adviceChecks", description = "일일 판단에 대한 사후 점검과 채점: 아침 점검(07:30, 미국 마감×β 예상 갭, REINFORCE/HOLD/CAUTION), 장중 점검(12:00 일치율·판정), "
      + "픽별 채점(진입·청산가, 수익률, 벤치마크 대비 초과수익, 상태), 지수·섹터·추세 콜 채점(적중·Brier). baseDate 생략 시 가장 최근 판단. "
      + "'그 판단 맞았어?', '점검 결과는?' 에 쓴다. 채점은 청산일이 지나야 생긴다.")
  public Map<String, Object> adviceChecks(@ToolParam(required = false, description = "판단 기준일 yyyy-MM-dd. 생략하면 가장 최근") String baseDate, ToolContext context) {
    return support.run("adviceChecks", context, () -> {
      Optional<AdviceHeader> found = resolve(AdviceKind.DAILY, baseDate);
      if (found.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_NO_DATA, "발행된 일일 판단이 없다", null);
      }
      AdviceHeader h = found.get();
      support.noteAsOf(context, h.baseDate());
      Map<String, Object> m = ToolJson.obj();
      m.put("adviceId", h.adviceId());
      m.put("baseDate", h.baseDate().toString());
      Optional<MorningCheckRow> morning = morningChecks.findByAdvice(h.adviceId());
      if (morning.isPresent()) {
        MorningCheckRow r = morning.get();
        Map<String, Object> x = ToolJson.obj();
        x.put("usDate", r.usDate().toString());
        ToolJson.put(x, "gapKospi", ToolJson.r4(r.gapKospi()));
        ToolJson.put(x, "gapKosdaq", ToolJson.r4(r.gapKosdaq()));
        ToolJson.put(x, "verdict", r.verdict() == null ? null : r.verdict().getCode());
        ToolJson.put(x, "detail", r.detailJson() == null || r.detailJson().isEmpty() ? null : r.detailJson());
        m.put("morning", x);
      }
      List<Map<String, Object>> intraday = new ArrayList<>();
      for (IntradayCheckRow r : intradayChecks.findByAdvice(h.adviceId())) {
        Map<String, Object> x = ToolJson.obj();
        ToolJson.put(x, "at", r.checkedAt() == null ? null : r.checkedAt().atZone(MarketClock.KST).toLocalDateTime().withNano(0).toString());
        ToolJson.put(x, "verdict", r.verdict() == null ? null : r.verdict().getCode());
        ToolJson.put(x, "agreement", ToolJson.r4(r.agreementRatio()));
        ToolJson.put(x, "comment", r.comment());
        intraday.add(x);
      }
      ToolJson.put(m, "intraday", intraday.isEmpty() ? null : intraday);
      List<Map<String, Object>> scores = new ArrayList<>();
      for (CandidateScoreRow s : scoreWriter.candidateScores(h.adviceId())) {
        // 판단 종류의 결정 호라이즌 행만 (DAILY·MORNING 5, H20 20 …) — 진단 호라이즌 행은 싣지 않는다
        if (s.horizonDays() != advisor.horizonOf(h.adviceKind()).orElse(advisor.getHorizonDays())) {
          continue;
        }
        Map<String, Object> x = ToolJson.obj();
        x.put("tk", s.ticker());
        ToolJson.put(x, "status", s.status() == null ? null : s.status().getCode());
        ToolJson.put(x, "stage", s.stage() == null ? null : s.stage().getCode());
        ToolJson.put(x, "entry", s.entryDate() == null ? null : s.entryDate().toString());
        ToolJson.put(x, "exit", s.exitDate() == null ? null : s.exitDate().toString());
        ToolJson.put(x, "ret", ToolJson.r4(s.ret()));
        ToolJson.put(x, "bench", ToolJson.r4(s.benchRet()));
        ToolJson.put(x, "excess", ToolJson.r4(s.excessRet()));
        scores.add(x);
      }
      ToolJson.put(m, "pickScores", scores.isEmpty() ? null : scores);
      List<Map<String, Object>> calls = new ArrayList<>();
      for (CallScoreRow c : scoreWriter.callScores(h.adviceId())) {
        Map<String, Object> x = ToolJson.obj();
        ToolJson.put(x, "subject", c.subjectType() == null ? null : c.subjectType().getCode());
        x.put("code", c.subjectCode());
        x.put("h", c.horizonDays());
        ToolJson.put(x, "predicted", c.predicted());
        ToolJson.put(x, "actual", c.actualDir());
        ToolJson.put(x, "hit", c.hit());
        ToolJson.put(x, "brier", ToolJson.r4(c.brier()));
        ToolJson.put(x, "actualRet", ToolJson.r4(c.actualRet()));
        calls.add(x);
      }
      ToolJson.put(m, "callScores", calls.isEmpty() ? null : calls);
      if (morning.isEmpty() && intraday.isEmpty() && scores.isEmpty() && calls.isEmpty()) {
        m.put("note", "아직 점검·채점이 없다(아침 점검 07:30, 장중 12:00, 채점은 청산일 이후)");
      }
      return m;
    });
  }

  @Tool(name = "screeningTop", description = "정량 스크리닝 상위 종목(판단과 같은 규칙: 시그널 백분위 가중 합, 1차 컷, 섹터당 상한). 종목마다 시그널 백분위(0~1)를 함께 주므로 "
      + "'왜 점수가 높지', '지금 정량 상위는?' 에 답할 수 있다. 백분위는 advisor.markets 시장 전체 기준이고 종목은 픽 유니버스(기본 KOSPI200 구성종목) 안에서만 고른다. "
      + "LLM 픽이 아니라 순수 정량 순위다.")
  public Map<String, Object> screeningTop(
      @ToolParam(required = false, description = MarketToolkit.AS_OF_DESC) String baseDate,
      @ToolParam(required = false, description = "종목 수 1~20 (기본 10)") Integer limit, ToolContext context) {
    return support.run("screeningTop", context, () -> {
      Optional<LocalDate> date = support.asOf(baseDate);
      if (date.isEmpty()) {
        return ToolJson.noData(null);
      }
      Optional<WeightSet> set = weightSets.active(advisor.getHorizonDays());
      if (set.isEmpty()) {
        return ToolJson.error(ToolJson.ERROR_INTERNAL, "활성 가중치 세트가 없다(advisor-seed.sql 미적용)", date.get());
      }
      int n = support.clampLimit(limit == null ? 10 : limit, SCREEN_CAP);
      ScreeningResult result = screening.screen(date.get(), set.get(), n, advisor.getMaxPerSector());
      if (result.candidates().isEmpty()) {
        return ToolJson.noData(date.get());
      }
      support.noteAsOf(context, date.get());
      Map<String, Object> m = ToolJson.obj();
      m.put("asOf", result.baseDate().toString());
      m.put("markets", advisor.getMarkets());
      m.put("pickUniverse", advisor.getPickUniverse().getCode());
      m.put("universe", result.universeSize());
      m.put("cut", result.cutSize());
      m.put("weightSetId", result.weightSetId());
      m.put("maxPerSector", advisor.getMaxPerSector());
      List<Map<String, Object>> items = new ArrayList<>();
      for (CandidateRow c : result.candidates()) {
        Map<String, Object> x = ToolJson.obj();
        x.put("rank", c.quantRank());
        x.put("tk", c.ticker());
        x.put("nm", c.stockName());
        ToolJson.put(x, "sector", c.sectorName());
        x.put("score", ToolJson.r4(c.quantScore()));
        if (c.signals() != null && !c.signals().isEmpty()) {
          Map<String, Object> sig = ToolJson.obj();
          for (Map.Entry<String, SignalValue> e : c.signals().entrySet()) {
            ToolJson.put(sig, e.getKey(), e.getValue() == null ? null : ToolJson.r4(e.getValue().pct()));
          }
          x.put("sig", sig);
        }
        items.add(x);
      }
      m.put("items", items);
      m.put("note", "sig 는 유니버스 안 백분위(1에 가까울수록 상위). score 는 가중 합 [-1,1]");
      return m;
    });
  }

  @Tool(name = "performanceSummary", description = "최근 weeks 주(기본 8, 최대 52)의 판단 성과 요약: 변형별(LIVE 발행본·QUANT_TOPN 정량 섀도·LLM_NOMEM·LLM_NONEWS) 픽 수·적중률·"
      + "평균 초과수익±표준오차·후보군 평균·부가가치(픽−후보군), 국면 콜 적중률·Brier skill, 추세 전망 적중률, 아침 점검 적중률, 확신 보정표. "
      + "'봇 성적 어때?', '적중률은?' 에 쓴다. n(표본 수)이 작으면 반드시 그 사실을 함께 말한다.")
  public Map<String, Object> performanceSummary(@ToolParam(required = false, description = "최근 몇 주 (1~52, 기본 8)") Integer weeks, ToolContext context) {
    return support.run("performanceSummary", context, () -> {
      int w = weeks == null || weeks <= 0 ? DEFAULT_WEEKS : Math.min(weeks, MAX_WEEKS);
      LocalDate to = MarketClock.today();
      LocalDate from = to.minusWeeks(w);
      Map<String, Object> m = ToolJson.obj();
      m.put("from", from.toString());
      m.put("to", to.toString());
      m.put("horizonDays", advisor.getHorizonDays());
      List<Map<String, Object>> variants = new ArrayList<>();
      int totalPicks = 0;
      for (AdvisorKpiService.VariantSummary v : kpi.variantSummaries(from, to)) {
        Map<String, Object> x = ToolJson.obj();
        x.put("variant", v.variant().getCode());
        x.put("advices", v.advices());
        x.put("picks", v.picks());
        ToolJson.put(x, "hitRate", ToolJson.r4(v.hitRate()));
        ToolJson.put(x, "meanExcess", ToolJson.r4(v.meanExcess()));
        ToolJson.put(x, "seExcess", ToolJson.r4(v.seExcess()));
        ToolJson.put(x, "poolMeanExcess", ToolJson.r4(v.poolMeanExcess()));
        ToolJson.put(x, "valueAdd", ToolJson.r4(v.valueAdd()));
        x.put("avoidPicks", v.avoidPicks());
        ToolJson.put(x, "avoidMeanExcess", ToolJson.r4(v.avoidMeanExcess()));
        variants.add(x);
        totalPicks += v.picks();
      }
      m.put("variants", variants);
      AdvisorKpiService.RegimeSummary regime = kpi.regimeSummary(AdviceVariant.LIVE, from, to);
      Map<String, Object> r = ToolJson.obj();
      r.put("calls", regime.calls());
      ToolJson.put(r, "hitRate", ToolJson.r4(regime.hitRate()));
      ToolJson.put(r, "meanBrier", ToolJson.r4(regime.meanBrier()));
      ToolJson.put(r, "brierSkill", ToolJson.r4(regime.brierSkill()));
      m.put("regime", r);
      AdvisorKpiService.TrendSummary trend = kpi.trendSummary(AdviceVariant.LIVE, from, to);
      Map<String, Object> t = ToolJson.obj();
      t.put("calls", trend.calls());
      ToolJson.put(t, "hitRate", ToolJson.r4(trend.hitRate()));
      t.put("invalidationCalls", trend.invalidationCalls());
      ToolJson.put(t, "invalidationHitRate", ToolJson.r4(trend.invalidationHitRate()));
      m.put("trendOutlook", t);
      AdvisorKpiService.MorningSummary morning = kpi.morningSummary(AdviceVariant.LIVE, from, to);
      Map<String, Object> mo = ToolJson.obj();
      mo.put("calls", morning.calls());
      ToolJson.put(mo, "hitRate", ToolJson.r4(morning.hitRate()));
      ToolJson.put(mo, "cautionRate", ToolJson.r4(morning.cautionRate()));
      m.put("morning", mo);
      List<Map<String, Object>> calibration = new ArrayList<>();
      for (AdvisorKpiService.CalibrationRow c : kpi.calibration(from, to)) {
        Map<String, Object> x = ToolJson.obj();
        x.put("conviction", ToolJson.r4(c.conviction()));
        x.put("n", c.n());
        ToolJson.put(x, "hitRate", ToolJson.r4(c.hitRate()));
        ToolJson.put(x, "meanExcess", ToolJson.r4(c.meanExcess()));
        calibration.add(x);
      }
      ToolJson.put(m, "calibration", calibration.isEmpty() ? null : calibration);
      m.put("note", totalPicks < 30 ? "표본(픽 " + totalPicks + "건)이 작아 통계적으로 의미 있는 판정이 불가능하다 — 단정하지 말 것" : "초과수익은 소속 시장 지수 대비, data_quality=OK 만 집계");
      return m;
    });
  }

  /**
   * 해당 종류의 LIVE 중 baseDate 가 있으면 그날 또는 그 이전 마지막, 없으면 오늘 기준 최신.
   */
  private Optional<AdviceHeader> resolve(AdviceKind kind, String baseDate) {
    LocalDate onOrBefore = ToolSupport.parseDate(baseDate).orElse(MarketClock.today());
    return adviceWriter.findLatest(kind, AdviceVariant.LIVE, onOrBefore);
  }
}
