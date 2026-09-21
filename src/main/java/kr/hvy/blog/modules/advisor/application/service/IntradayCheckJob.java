package kr.hvy.blog.modules.advisor.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.IntradayCheckMessage;
import kr.hvy.blog.modules.advisor.client.llm.PickNoteResponse;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CitedFeature;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.client.KisCallContext;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisIndexPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 장중 점검 (INTRADAY, 평일 12:00 KST). 직전 영업일 LIVE 판단의 지수 방향·픽 방향을 KIS 현재가(전일 대비율)와 대조해 일치율·판정을 보고하고(2026-09-13),
 * 픽마다 12:00 편차를 정량·분류·회고해 오답노트(tb_advisor_pick_note)로 남긴다(note-v1, 2026-09-21).
 * <pre>
 * INDEX(격리: 지수 2 + 벤치, 코드별 실패는 삼킴) → PICKS(필수: KIS 조회·정량·PickDeviation 분류, 종목 1건 실패는 삼킴)
 * → REFLECT(격리: FLAT 아닌 픽만 assist 1회, 전부 FLAT 이거나 note.enabled=false 면 SKIPPED) → SAVE(필수: check 1행) → NOTES(격리: note N행) → PUBLISH(격리)
 * </pre>
 * 어느 격리 단계가 죽어도 일치율·판정·Slack 은 유지되고 run 은 PARTIAL — 노트 테이블이 아직 없어도(배포 전) 기존 점검은 산다. 12:00 값은 노트 테이블에만 저장하고
 * 픽·채점·IC·교훈 SQL 은 읽지 않는다(룩어헤드 경계). 회고 문장(deviation·why·hypothesis)은 DB·Slack·관리자 기록용이며 다음 판단 프롬프트에는 T+5 로 확정된
 * 결정론 빈도표만 들어간다(사용자 결정 ①). 호출은 지수 2(+벤치) + 픽 ≤10 을 리미터 간격으로 순차 실행한다. 휴장일·판단 없음·KIS 키 없음이면 SKIPPED.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class IntradayCheckJob implements AdvisorJob {

  static final double ON_TRACK_RATIO = 0.6;
  static final double OFF_TRACK_RATIO = 0.3;
  /** 지수 NEUTRAL 예측이 맞다고 볼 장중 등락률 절대값 상한(%) */
  static final double INDEX_NEUTRAL_BAND_PCT = 0.3;
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  static final List<String> MARKET_INDEX_CODES = List.of("0001", "1001");
  static final String DEFAULT_BENCH = "0001";
  /** 정규 점검 창(KST). 밖이면 노트 tags.offHours=true — 장 마감 뒤 수동 실행은 현재가=종가라 "반나절" 해석이 깨진다 */
  static final LocalTime REGULAR_FROM = LocalTime.of(11, 30);
  static final LocalTime REGULAR_TO = LocalTime.of(12, 30);
  /** 회고 문장 저장 길이 (컬럼 폭) */
  static final int DEVIATION_MAX = 300;
  static final int WHY_MAX = 600;
  static final int HYPOTHESIS_MAX = 300;
  /** Slack 픽 줄의 why 요약 길이(자) */
  static final int WHY_SUMMARY = 60;
  /** hypothesis 에 금지된 날짜 표기: 2026-09-21 · 2026.9.21 · 9월 21일 · 2026년 */
  static final Pattern DATE_MENTION = Pattern.compile("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}월\\s?\\d{1,2}일|\\d{4}년");
  static final String EXCESS_BASIS_MIXED = "MIXED";

  private final AdvisorProperties properties;
  private final KisProperties kisProperties;
  private final KisMarketDataPort marketData;
  private final MarketCalendarService calendar;
  private final AdviceWriter adviceWriter;
  private final IntradayCheckWriter checkWriter;
  private final AdvisorNotifier notifier;
  private final MarketJudgeClient assist;
  private final PickNoteRepository notes;
  private final PromptResources prompts;
  private final Clock clock;

  /**
   * 유일한 생성자. assist 는 AdvisorAiConfig 의 assistClient 빈, clock 은 Clock 빈이 있으면 그것, 없으면 시스템 UTC(테스트는 고정 Clock 으로 장외 판정을 검사한다).
   * 생성자를 둘(Spring 용·테스트 용) 두면 @Autowired 없는 Spring 은 기본 생성자로 후퇴해 기동이 실패하므로(2026-09-13) 하나만 유지한다.
   */
  public IntradayCheckJob(AdvisorProperties properties, KisProperties kisProperties, KisMarketDataPort marketData, MarketCalendarService calendar,
      AdviceWriter adviceWriter, IntradayCheckWriter checkWriter, AdvisorNotifier notifier,
      @Qualifier(MarketJudgeClient.ASSIST_BEAN) MarketJudgeClient assist, PickNoteRepository notes, PromptResources prompts,
      ObjectProvider<Clock> clockProvider) {
    this.properties = properties;
    this.kisProperties = kisProperties;
    this.marketData = marketData;
    this.calendar = calendar;
    this.adviceWriter = adviceWriter;
    this.checkWriter = checkWriter;
    this.notifier = notifier;
    this.assist = assist;
    this.notes = notes;
    this.prompts = prompts;
    this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
  }

  /** 지수 1개의 현재가 스냅샷 (시가는 KIS 가 주지 않으면 null) */
  record IndexQuote(String code, Double current, Double open, Double changeRatePct) {

    Double sinceOpen() {
      return PickDeviation.sinceOpen(current, open);
    }
  }

  /**
   * 픽 1개의 12:00 관측. price 가 null 이면(조회 실패·빈 응답) 노트를 만들지 않는다. noteClass 는 price 가 있을 때만 non-null.
   */
  record PickObs(PickRow pick, CandidateRow candidate, Double price, Double open, Double changeRatePct, Double gap, Double sinceOpen, String benchCode,
                 Double benchRatePct, Double benchSinceOpen, PickDeviation.Excess excess, Double z, PickNoteClass noteClass, Boolean agree, String error) {

    String name() {
      return candidate == null || candidate.stockName() == null ? pick.ticker() : candidate.stockName();
    }

    /** excess 와 같은 기준의 픽 자체 등락(소수) */
    Double move() {
      return move(excess, sinceOpen, changeRatePct);
    }

    static Double move(PickDeviation.Excess excess, Double sinceOpen, Double changeRatePct) {
      if (excess == null) {
        return null;
      }
      return PickDeviation.BASIS_OPEN.equals(excess.basis()) ? sinceOpen : changeRatePct == null ? null : changeRatePct / 100.0;
    }

    static PickObs failed(PickRow pick, CandidateRow candidate, String benchCode, String error) {
      return new PickObs(pick, candidate, null, null, null, null, null, benchCode, null, null, null, null, null, null, error);
    }
  }

  /** 가드를 통과한 회고 1건 */
  record Reflection(String deviation, String why, String hypothesis, List<String> signals, String sector, String regime) {
  }

  /** 회고 호출 결과: 티커별 회고 + 모델 */
  record Reflected(Map<String, Reflection> byTicker, String model) {
  }

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.INTRADAY;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate today = execution.baseDate();
    if (!calendar.isTradingDay(today)) {
      execution.skip("휴장일 " + today);
      return;
    }
    if (!kisProperties.isConfigured()) {
      execution.skip("KIS 앱키가 없어 현재가를 조회할 수 없습니다");
      return;
    }
    Optional<AdviceHeader> latest = adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1));
    if (latest.isEmpty() || latest.get().baseDate().isBefore(calendar.lastTradingDayOnOrBefore(today.minusDays(1)))) {
      execution.skip("점검할 직전 영업일 LIVE 판단이 없습니다");
      return;
    }
    AdviceHeader advice = latest.get();
    List<PickRow> picks = adviceWriter.picks(advice.adviceId());
    Map<String, CandidateRow> candidates = new LinkedHashMap<>();
    adviceWriter.candidates(advice.adviceId()).forEach(c -> candidates.put(c.ticker(), c));
    KisCallContext context = KisCallContext.of(execution.runId());
    AdvisorSteps steps = new AdvisorSteps(execution);
    Instant now = clock.instant();
    boolean offHours = isOffHours(now);
    double zThreshold = properties.getNote().getZThreshold();

    // ① 지수 (격리): 시장 지수 2개 + 픽 벤치 지수. 코드별 실패는 삼키고 기록한다
    Map<String, IndexQuote> quotes = new LinkedHashMap<>();
    Map<String, Object> indexJson = new LinkedHashMap<>();
    List<String> indexLines = new ArrayList<>();
    LinkedHashSet<String> indexCodes = new LinkedHashSet<>(MARKET_INDEX_CODES);
    picks.forEach(p -> indexCodes.add(benchCode(candidates.get(p.ticker()))));
    steps.run("INDEX", () -> {
      for (String code : indexCodes) {
        boolean market = MARKET_INDEX_CODES.contains(code);
        DirectionCall predicted = "0001".equals(code) ? advice.kospiDir() : "1001".equals(code) ? advice.kosdaqDir() : null;
        try {
          KisIndexPriceResponse.Output out = marketData.fetchIndexPrice(code, context);
          IndexQuote quote = new IndexQuote(code, parse(out == null ? null : out.currentValue()), parse(out == null ? null : out.openValue()),
              parse(out == null ? null : out.changeRate()));
          quotes.put(code, quote);
          if (market) {
            Boolean agree = quote.changeRatePct() == null || predicted == null ? null : indexAgrees(predicted, quote.changeRatePct());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", quote.current());
            m.put("changeRate", quote.changeRatePct());
            m.put("open", quote.open());
            m.put("predicted", predicted == null ? null : predicted.getCode());
            m.put("agree", agree);
            indexJson.put(code, m);
            indexLines.add(String.format("%s %s%s", indexName(code), quote.changeRatePct() == null ? "-" : String.format("%+.2f%%", quote.changeRatePct()),
                agree == null ? "" : (agree ? " ✓" : " ✗")));
          }
        } catch (RuntimeException e) {
          execution.recordFailure("INDEX:" + code, e.toString());
          if (market) {
            indexLines.add(indexName(code) + " 조회 실패");
          }
        }
      }
    });

    // ② 픽 (필수): 현재가·시가·정량·분류. 종목 1건 실패는 삼키고 나머지로 판정한다
    List<PickObs> observations = new ArrayList<>();
    steps.runOrThrow("PICKS", () -> {
      for (PickRow pick : picks) {
        observations.add(observe(pick, candidates.get(pick.ticker()), quotes, context, execution, zThreshold));
      }
    });
    List<Map<String, Object>> pickJson = observations.stream().map(IntradayCheckJob::pickJson).toList();
    int agreed = (int) observations.stream().filter(o -> Boolean.TRUE.equals(o.agree())).count();
    int total = (int) observations.stream().filter(o -> o.agree() != null).count();
    Double ratio = total == 0 ? null : (double) agreed / total;
    IntradayVerdict verdict = ratio == null ? IntradayVerdict.MIXED : ratio >= ON_TRACK_RATIO ? IntradayVerdict.ON_TRACK
        : ratio < OFF_TRACK_RATIO ? IntradayVerdict.OFF_TRACK : IntradayVerdict.MIXED;
    String comment = comment(verdict, pickJson, candidates);
    String excessBasis = excessBasis(observations);

    // ③ 회고 (격리): FLAT 이 아닌 픽만 |z| 큰 순으로 상한까지 모아 assist 1회
    List<PickObs> reflectable = observations.stream()
        .filter(o -> o.noteClass() != null && o.noteClass() != PickNoteClass.FLAT)
        .sorted(Comparator.comparingDouble((PickObs o) -> o.z() == null ? 0 : Math.abs(o.z())).reversed())
        .limit(Math.max(0, properties.getNote().getMaxReflectPicks()))
        .toList();
    final Reflected[] reflected = new Reflected[1];
    if (!properties.getNote().isEnabled()) {
      steps.skip("REFLECT", "회고 비활성 (advisor.note.enabled=false)");
    } else if (reflectable.isEmpty()) {
      steps.skip("REFLECT", "회고 대상 없음 (전부 FLAT 또는 조회 실패)");
    } else {
      steps.run("REFLECT", () -> reflected[0] = reflect(execution, advice, reflectable, candidates.values(), quotes, excessBasis, now));
    }
    Map<String, Reflection> reflections = reflected[0] == null ? Map.of() : reflected[0].byTicker();
    String noteModel = reflected[0] == null ? null : reflected[0].model();

    // ④ 저장 (필수): 점검 1행
    IntradayCheckRow checkRow = IntradayCheckRow.builder().adviceId(advice.adviceId()).runId(execution.runId()).checkedAt(now)
        .indexJson(indexJson).pickJson(pickJson).agreementRatio(ratio).verdict(verdict).comment(comment).build();
    final long[] checkId = new long[1];
    steps.runOrThrow("SAVE", () -> checkId[0] = checkWriter.insert(checkRow));

    // ⑤ 노트 (격리): note N행, append-only. 별도 트랜잭션이라 여기가 죽어도 점검·Slack 은 산다
    final int[] saved = new int[1];
    steps.run("NOTES", () -> {
      List<PickNoteRow> rows = new ArrayList<>();
      for (PickObs o : observations) {
        if (o.noteClass() != null) {
          rows.add(noteRow(o, reflections.get(o.pick().ticker()), noteModel, checkId[0], advice, execution.runId(), now, offHours));
        }
      }
      saved[0] = notes.insertAll(rows);
    });
    execution.putMetadata("checkId", checkId[0]);
    execution.putMetadata("agreement", ratio);
    execution.putMetadata("verdict", verdict.getCode());
    execution.putMetadata("notes", saved[0]);
    execution.putMetadata("reflected", reflections.size());
    execution.putMetadata("excessBasis", excessBasis);
    if (offHours) {
      execution.putMetadata("offHours", true);
    }

    // ⑥ 발행 (격리)
    steps.run("PUBLISH", () -> {
      IntradayCheckMessage message = IntradayCheckMessage.builder().baseDate(advice.baseDate().toString())
          .checkedAt(LocalTime.ofInstant(now, MarketClock.KST).format(TIME)).indexLine(String.join(" / ", indexLines)).agreed(agreed).total(total)
          .verdict(verdict).comment(comment).runId(execution.runId()).adviceId(advice.adviceId())
          .pickLines(pickLines(observations, reflections)).offHours(offHours).build();
      if (!notifier.publish(message)) {
        execution.warn("장중 점검 Slack 발행 실패 (check=" + checkId[0] + ")");
      }
    });
  }

  /**
   * 픽 1개 관측: KIS 현재가 스냅샷 → sinceOpen·gap → 벤치 지수 대비 초과(시가 기준, 없으면 전일 종가 기준 폴백) → z·zMove(후보 vol20) → 분류.
   * 조회 예외는 부분 실패로 기록하고 error 만 남긴다. 현재가가 비어 있으면(빈 응답) 노트 없이 기록만 한다.
   */
  PickObs observe(PickRow pick, CandidateRow candidate, Map<String, IndexQuote> quotes, KisCallContext context, AdvisorExecution execution,
      double zThreshold) {
    String benchCode = benchCode(candidate);
    IndexQuote bench = quotes.get(benchCode);
    try {
      KisPriceResponse.Output out = marketData.fetchPrice(pick.ticker(), context);
      Double price = parse(out == null ? null : out.currentPrice());
      Double open = parse(out == null ? null : out.openPrice());
      Double base = parse(out == null ? null : out.basePrice());
      Double rate = parse(out == null ? null : out.changeRate());
      Double sinceOpen = PickDeviation.sinceOpen(price, open);
      Double gap = PickDeviation.gap(open, base);
      Double benchRate = bench == null ? null : bench.changeRatePct();
      Double benchSinceOpen = bench == null ? null : bench.sinceOpen();
      PickDeviation.Excess excess = PickDeviation.excess(sinceOpen, benchSinceOpen, rate, benchRate);
      Double vol20 = feature(candidate, "vol20");
      Double z = PickDeviation.z(excess, vol20);
      Double move = PickObs.move(excess, sinceOpen, rate);
      Double zMove = excess == null ? null : PickDeviation.zOf(move, excess.basis(), vol20);
      Boolean agree = rate == null ? null : pick.direction() == PickDirection.LONG ? rate > 0 : rate <= 0;
      PickNoteClass cls = price == null ? null
          : PickDeviation.classify(pick.direction(), move, excess == null ? null : excess.value(), z, zMove, zThreshold);
      return new PickObs(pick, candidate, price, open, rate, gap, sinceOpen, benchCode, benchRate, benchSinceOpen, excess, z, cls, agree, null);
    } catch (RuntimeException e) {
      execution.recordFailure("PICK:" + pick.ticker(), e.toString());
      return PickObs.failed(pick, candidate, benchCode, e.getClass().getSimpleName());
    }
  }

  /**
   * assist 1회 호출: 회고 대상 픽의 thesis·risk·정량을 넘기고 픽별 회고를 받아 가드(티커·종목명·날짜 언급 → hypothesis null, 길이·제어문자)를 거친다.
   * 종목명 가드는 그 판단의 후보 종목명 전체를 본다(회고 대상이 아닌 후보를 가설에 끌어들이는 것도 막는다).
   */
  Reflected reflect(AdvisorExecution execution, AdviceHeader advice, List<PickObs> targets, Iterable<CandidateRow> allCandidates,
      Map<String, IndexQuote> quotes, String excessBasis, Instant now) {
    List<String> tickers = targets.stream().map(o -> o.pick().ticker()).toList();
    List<String> sectors = targets.stream().map(o -> o.candidate() == null ? null : o.candidate().sectorCode()).filter(s -> s != null).distinct().toList();
    Set<String> names = new LinkedHashSet<>();
    for (CandidateRow c : allCandidates) {
      if (c.stockName() != null && !c.stockName().isBlank()) {
        names.add(c.stockName());
      }
    }
    String schema = NoteSchemaFactory.schemaJson(tickers, sectors);
    Map<String, Object> payload = reflectPayload(advice, targets, quotes, excessBasis, now);
    MarketJudgeClient.CallResult<PickNoteResponse> r = assist.call(prompts.noteSystem(), AdvisorJson.write(payload), schema, PickNoteResponse.class);
    execution.recordLlmUsage(r.model(), PromptResources.NOTE_VERSION, r.usage(), r.reasoningTokens(), r.cachedTokens());
    Map<String, Reflection> result = new LinkedHashMap<>();
    if (r.value() != null && r.value().notes() != null) {
      for (PickNoteResponse.Note n : r.value().notes()) {
        if (n == null || n.ticker() == null || !tickers.contains(n.ticker()) || result.containsKey(n.ticker())) {
          continue;
        }
        result.put(n.ticker(), guard(n, names));
      }
    }
    return new Reflected(result, r.model());
  }

  /**
   * 회고 입력 JSON: 기준일·점검 시각·국면·지수 등락률과 픽별 {ticker,name,direction,conviction,thesis,risk,citedFeatures,sector,secCons,class,basis,sinceOpen,excess,z,gap,benchRate}.
   * 종목 뉴스·외부 지식은 넣지 않는다.
   */
  static Map<String, Object> reflectPayload(AdviceHeader advice, List<PickObs> targets, Map<String, IndexQuote> quotes, String excessBasis, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("baseDate", advice.baseDate().toString());
    payload.put("checkedAt", now.atZone(MarketClock.KST).format(DATE_TIME));
    payload.put("regime", advice.regimeCode() == null ? null : advice.regimeCode().getCode());
    Map<String, Object> market = new LinkedHashMap<>();
    market.put("kospi", quotes.get("0001") == null ? null : quotes.get("0001").changeRatePct());
    market.put("kosdaq", quotes.get("1001") == null ? null : quotes.get("1001").changeRatePct());
    payload.put("market", market);
    payload.put("excessBasis", excessBasis);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (PickObs o : targets) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("ticker", o.pick().ticker());
      m.put("name", o.name());
      m.put("direction", o.pick().direction().getCode());
      m.put("conviction", o.pick().conviction());
      m.put("thesis", o.pick().thesis());
      m.put("risk", o.pick().riskNote());
      List<Map<String, Object>> cited = new ArrayList<>();
      if (o.pick().cited() != null) {
        for (CitedFeature c : o.pick().cited()) {
          cited.add(Map.of("name", c.name(), "value", c.value()));
        }
      }
      m.put("citedFeatures", cited);
      m.put("sector", o.candidate() == null ? null : o.candidate().sectorCode());
      m.put("secCons", secConsOf(o.candidate()));
      m.put("class", o.noteClass().getCode());
      m.put("basis", o.excess() == null ? null : o.excess().basis());
      m.put("sinceOpen", round4(o.sinceOpen()));
      m.put("excess", o.excess() == null ? null : round4(o.excess().value()));
      m.put("z", o.z() == null ? null : Math.round(o.z() * 100) / 100.0);
      m.put("gap", round4(o.gap()));
      m.put("benchRate", o.benchRatePct());
      rows.add(m);
    }
    payload.put("picks", rows);
    return payload;
  }

  /**
   * 회고 가드: 제어문자 제거·컬럼 폭 절단, hypothesis 에 6자리 종목코드·종목명·날짜가 있으면 null(deviation·why 는 유지), signals 는 SignalCode 코드만.
   */
  static Reflection guard(PickNoteResponse.Note n, Set<String> stockNames) {
    String deviation = clean(n.deviation(), DEVIATION_MAX);
    String why = clean(n.why(), WHY_MAX);
    String candidate = clean(n.hypothesis(), HYPOTHESIS_MAX);
    String hypothesis = candidate != null && (LessonService.TICKER_MENTION.matcher(candidate).find() || DATE_MENTION.matcher(candidate).find()
        || stockNames.stream().anyMatch(name -> !name.isBlank() && candidate.contains(name))) ? null : candidate;
    List<String> signals = new ArrayList<>();
    if (n.tags() != null && n.tags().signals() != null) {
      for (String s : n.tags().signals()) {
        if (s != null && !signals.contains(s) && isSignalCode(s)) {
          signals.add(s);
        }
      }
    }
    return new Reflection(deviation, why, hypothesis, signals, n.tags() == null ? null : n.tags().sector(), n.tags() == null ? null : n.tags().regime());
  }

  /**
   * 노트 1행: 정량·분류는 항상, 회고는 있을 때만. tags 는 {signals, sector, regime, excessBasis, offHours, benchCode, benchSinceOpen, secCons}.
   * sector·regime 은 회고가 준 값을 우선하고 없으면 후보 섹터·판단 국면으로 채운다(결정론).
   */
  static PickNoteRow noteRow(PickObs o, Reflection r, String model, long checkId, AdviceHeader advice, Long runId, Instant now, boolean offHours) {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("signals", r == null ? List.of() : r.signals());
    String sector = r != null && r.sector() != null ? r.sector() : o.candidate() == null ? null : o.candidate().sectorCode();
    String regime = r != null && r.regime() != null ? r.regime() : advice.regimeCode() == null ? null : advice.regimeCode().getCode();
    tags.put("sector", sector);
    tags.put("regime", regime);
    tags.put("excessBasis", o.excess() == null ? null : o.excess().basis());
    tags.put("offHours", offHours);
    tags.put("benchCode", o.benchCode());
    tags.put("benchSinceOpen", o.benchSinceOpen());
    tags.put("secCons", secConsOf(o.candidate()));
    return PickNoteRow.builder()
        .adviceId(advice.adviceId()).checkId(checkId).ticker(o.pick().ticker()).baseDate(advice.baseDate()).notedAt(now)
        .direction(o.pick().direction()).conviction(o.pick().conviction())
        .openPrice(o.open()).currentPrice(o.price()).changeRate(o.changeRatePct()).gapRate(o.gap()).sinceOpenRate(o.sinceOpen())
        .benchRate(o.benchRatePct()).excessRate(o.excess() == null ? null : o.excess().value()).zScore(o.z()).noteClass(o.noteClass())
        .deviation(r == null ? null : r.deviation()).why(r == null ? null : r.why()).hypothesis(r == null ? null : r.hypothesis())
        .tags(tags).status(PickNoteStatus.OPEN).model(r == null ? null : model).runId(runId)
        .build();
  }

  /**
   * 후보의 섹터 3구간 일관성(1/0/null). 후보 스냅샷(feature_json)에는 스크리닝이 넣은 secRs5/secRs20/secRs60 만 있고 secCons 키는 없으므로
   * 프롬프트와 같은 정의({@link AdvicePromptBuilder#secCons})로 파생한다 — recentOutcomes 의 행 키(class × secCons)가 이 값에 걸리므로 정의를 하나로 둔다.
   */
  static Integer secConsOf(CandidateRow candidate) {
    return candidate == null ? null : AdvicePromptBuilder.secCons(candidate.features());
  }

  /**
   * Slack 픽 줄(≤10): "종목명 시가대비 +0.8% · 지수대비 +0.5% (1.3σ) · IDIOSYNCRATIC · why 요약". 지수 비교가 불가하면 전일대비만.
   */
  static List<String> pickLines(List<PickObs> observations, Map<String, Reflection> reflections) {
    List<String> lines = new ArrayList<>();
    for (PickObs o : observations) {
      if (o.price() == null) {
        continue;
      }
      StringBuilder sb = new StringBuilder(o.name());
      if (o.excess() != null && o.move() != null) {
        boolean open = PickDeviation.BASIS_OPEN.equals(o.excess().basis());
        sb.append(String.format(" %s %+.1f%% · 지수대비 %+.1f%%", open ? "시가대비" : "전일대비", o.move() * 100, o.excess().value() * 100));
        if (o.z() != null) {
          sb.append(String.format(" (%.1fσ)", o.z()));
        }
      } else if (o.changeRatePct() != null) {
        sb.append(String.format(" 전일대비 %+.1f%% · 지수 비교 불가", o.changeRatePct()));
      }
      if (o.noteClass() != null) {
        sb.append(" · ").append(o.noteClass().getCode());
      }
      Reflection r = reflections.get(o.pick().ticker());
      if (r != null && r.why() != null) {
        sb.append(" · ").append(StringUtils.abbreviate(r.why(), WHY_SUMMARY));
      }
      lines.add(sb.toString());
      if (lines.size() >= IntradayCheckMessage.MAX_PICK_LINES) {
        break;
      }
    }
    return lines;
  }

  /**
   * 점검 저장 JSON 의 픽 1건: 기존 {ticker,direction,price,changeRate,agree|error} + 12:00 정량 {open,sinceOpen,excess,z,class}(있을 때만).
   */
  static Map<String, Object> pickJson(PickObs o) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ticker", o.pick().ticker());
    m.put("direction", o.pick().direction().getCode());
    if (o.error() != null) {
      m.put("error", o.error());
      return m;
    }
    m.put("price", o.price());
    m.put("changeRate", o.changeRatePct());
    if (o.agree() != null) {
      m.put("agree", o.agree());
    }
    if (o.open() != null) {
      m.put("open", o.open());
    }
    if (o.sinceOpen() != null) {
      m.put("sinceOpen", round4(o.sinceOpen()));
    }
    if (o.excess() != null) {
      m.put("excess", round4(o.excess().value()));
    }
    if (o.z() != null) {
      m.put("z", Math.round(o.z() * 100) / 100.0);
    }
    if (o.noteClass() != null) {
      m.put("class", o.noteClass().getCode());
    }
    return m;
  }

  /**
   * run 메타 excessBasis: 관측 전부가 같은 기준이면 그 값, 섞였으면 MIXED(픽별 기준은 노트 tags·회고 입력 basis 에), 초과가 하나도 없으면 null.
   */
  static String excessBasis(List<PickObs> observations) {
    Set<String> bases = new LinkedHashSet<>();
    observations.stream().filter(o -> o.excess() != null).forEach(o -> bases.add(o.excess().basis()));
    return bases.isEmpty() ? null : bases.size() == 1 ? bases.iterator().next() : EXCESS_BASIS_MIXED;
  }

  /**
   * 정규 점검 창(KST 11:30~12:30) 밖인가.
   */
  static boolean isOffHours(Instant now) {
    LocalTime t = LocalTime.ofInstant(now, MarketClock.KST);
    return t.isBefore(REGULAR_FROM) || t.isAfter(REGULAR_TO);
  }

  /**
   * 지수 예측 일치: UP 은 양, DOWN 은 음, NEUTRAL 은 |등락률| < 0.3%.
   */
  static boolean indexAgrees(DirectionCall predicted, double ratePct) {
    return switch (predicted) {
      case UP -> ratePct > 0;
      case DOWN -> ratePct < 0;
      case NEUTRAL -> Math.abs(ratePct) < INDEX_NEUTRAL_BAND_PCT;
    };
  }

  /**
   * 규칙 기반 한 줄 코멘트: 판정 + 가장 어긋난 픽 최대 2개.
   */
  static String comment(IntradayVerdict verdict, List<Map<String, Object>> picks, Map<String, CandidateRow> candidates) {
    List<String> off = new ArrayList<>();
    for (Map<String, Object> p : picks) {
      if (Boolean.FALSE.equals(p.get("agree")) && p.get("changeRate") instanceof Double rate) {
        CandidateRow c = candidates.get(String.valueOf(p.get("ticker")));
        String name = c == null || c.stockName() == null ? String.valueOf(p.get("ticker")) : c.stockName();
        off.add(String.format("%s %+.1f%%", name, rate));
        if (off.size() == 2) {
          break;
        }
      }
    }
    String base = switch (verdict) {
      case ON_TRACK -> "판단 유지.";
      case MIXED -> "혼조 — 장 마감 채점으로 확인.";
      case OFF_TRACK -> "예측과 반대로 움직임 — 마감 후 채점 주시.";
    };
    return off.isEmpty() ? base : base + " 어긋난 픽: " + String.join(", ", off);
  }

  static String benchCode(CandidateRow candidate) {
    return candidate == null || candidate.benchIndexCode() == null || candidate.benchIndexCode().isBlank() ? DEFAULT_BENCH : candidate.benchIndexCode();
  }

  static String indexName(String code) {
    return "0001".equals(code) ? "KOSPI" : "1001".equals(code) ? "KOSDAQ" : code;
  }

  static Double feature(CandidateRow candidate, String key) {
    if (candidate == null || candidate.features() == null) {
      return null;
    }
    return candidate.features().get(key) instanceof Number n ? n.doubleValue() : null;
  }

  static boolean isSignalCode(String code) {
    for (SignalCode s : SignalCode.values()) {
      if (s.getCode().equals(code)) {
        return true;
      }
    }
    return false;
  }

  /** 제어문자 제거·공백 정리·절단. 비면 null */
  static String clean(String text, int max) {
    if (text == null) {
      return null;
    }
    String cleaned = text.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").trim();
    return cleaned.isEmpty() ? null : StringUtils.abbreviate(cleaned, max);
  }

  static Double round4(Double v) {
    return v == null ? null : Math.round(v * 1e4) / 1e4;
  }

  static Double parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
