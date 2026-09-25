package kr.hvy.blog.modules.advisor.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.MorningAdviceMessage;
import kr.hvy.blog.modules.advisor.client.llm.MorningAdviceResponse;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.SectorCall;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 아침 재판정 (MORNING_ADVISE, 평일 07:40 KST — 07:30 MORNING_CHECK 뒤, 08:50 발행 마감).
 * <pre>
 * 게이트(영업일·마감·직전 영업일 DAILY LIVE·중복·저녁 입력 스냅샷) → 밤사이 블록(미국 r1·β 갭·환율·섹터 연동 심볼·07:30 점검) →
 * LLM 판단(strict, 매일 호출) → 가드(KEEP/DROP 은 저녁 픽, ADD 는 저녁 후보) → MORNING LIVE 저장(parent·diff_json·픽 action) → Slack 발행
 * </pre>
 * 저녁과 <b>같은 base_date·진입(D+1 시가)·청산 창</b>이라 ScoreJob 이 같은 창으로 채점하고, 같은 기준일 MORNING − DAILY 픽 평균 초과가 곧
 * 밤사이 정보의 가치를 재는 대응 비교다(AdvisorKpiService.morningVsDaily). 그래서 섀도가 필요 없다.
 * <p>
 * 룩어헤드 경계: 미국 데이터는 현지일 &lt; 오늘(= us_date ≤ 오늘−1, SQL 과 여기서 한 번 더), 국내 데이터(β·σ)는 저녁 기준일 이하만.
 * 트리거 플래그(|예상 갭| ≥ σ₁d, 섹터 연동 심볼 |z| ≥ 2, 07:30 CAUTION)는 LLM 호출 여부를 가르지 않고 diff_json.triggers 에만 남겨
 * 트리거일/비트리거일 사후 분석에 쓴다(2026-09-25 사용자 결정 — 매일 호출).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class MorningAdviseJob implements AdvisorJob {

  static final List<String> INDEX_CODES = MorningCheckJob.INDEX_CODES;
  static final String FX_SYMBOL = MorningCheckJob.FX_SYMBOL;

  private final AdvisorProperties properties;
  private final MarketCalendarService calendar;
  private final AdviceWriter adviceWriter;
  private final PromptInputWriter promptInputs;
  private final MorningCheckWriter morningChecks;
  private final GlobalLinkService links;
  private final PromptResources prompts;
  private final MarketJudgeClient judge;
  private final AdvisorNotifier notifier;
  private final MorningAdviceGuard guard;
  private final Clock clock;

  /**
   * 밤사이 블록: 프롬프트 overnight(json)와 사후 분석용 트리거(triggers). usDate 는 반영한 미국 세션 현지일(없으면 null).
   */
  record Overnight(Map<String, Object> json, Map<String, Object> triggers, LocalDate usDate, boolean usClosed, int lookaheadDropped) {
  }

  /**
   * 유일한 생성자. judge 는 AdvisorAiConfig 의 judgeClient 빈(저녁 판단과 같은 모델), clock 은 Clock 빈이 있으면 그것, 없으면 시스템 UTC —
   * 테스트는 고정 Clock 으로 08:50 발행 마감을 검사한다(IntradayCheckJob 과 같은 방식).
   */
  public MorningAdviseJob(AdvisorProperties properties, MarketCalendarService calendar, AdviceWriter adviceWriter, PromptInputWriter promptInputs,
      MorningCheckWriter morningChecks, GlobalLinkService links, PromptResources prompts, @Qualifier(MarketJudgeClient.JUDGE_BEAN) MarketJudgeClient judge,
      AdvisorNotifier notifier, ObjectProvider<Clock> clockProvider) {
    this.properties = properties;
    this.calendar = calendar;
    this.adviceWriter = adviceWriter;
    this.promptInputs = promptInputs;
    this.morningChecks = morningChecks;
    this.links = links;
    this.prompts = prompts;
    this.judge = judge;
    this.notifier = notifier;
    this.guard = new MorningAdviceGuard(properties);
    this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
  }

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.MORNING_ADVISE;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate today = execution.baseDate();
    execution.putMetadata("adviceKind", AdviceKind.MORNING.getCode());
    if (!calendar.isTradingDay(today)) {
      execution.skip("휴장일 " + today);
      return;
    }
    Optional<String> late = pastDeadline(today);
    if (late.isPresent()) {
      execution.skip(late.get());
      return;
    }
    Optional<AdviceHeader> latest = adviceWriter.findLatest(AdviceKind.DAILY, AdviceVariant.LIVE, today.minusDays(1));
    if (latest.isEmpty() || latest.get().baseDate().isBefore(calendar.lastTradingDayOnOrBefore(today.minusDays(1)))) {
      execution.skip("재판정할 직전 영업일 저녁 LIVE 판단이 없습니다");
      return;
    }
    AdviceHeader evening = latest.get();
    execution.putMetadata("parentAdviceId", evening.adviceId());
    Optional<AdviceHeader> existing = adviceWriter.find(evening.baseDate(), AdviceKind.MORNING, AdviceVariant.LIVE);
    if (existing.isPresent()) {
      execution.skip("이미 아침 재판정이 있습니다: " + evening.baseDate() + " (advice=" + existing.get().adviceId() + ")");
      return;
    }
    Optional<PromptInputRow> snapshot = promptInputs.find(evening.runId(), AdviceVariant.LIVE);
    List<PickRow> eveningPicks = adviceWriter.picks(evening.adviceId());
    if (snapshot.isEmpty() || eveningPicks.isEmpty()) {
      execution.skip("저녁 입력 스냅샷 또는 픽이 없습니다 (advice=" + evening.adviceId() + ", run=" + evening.runId() + ")");
      return;
    }
    List<CandidateRow> candidates = adviceWriter.candidates(evening.adviceId());
    AdvisorSteps steps = new AdvisorSteps(execution);

    // ① 밤사이 블록 (필수 — 없으면 재판정할 정보가 없다)
    final Overnight[] box = new Overnight[1];
    steps.runOrThrow("OVERNIGHT", () -> box[0] = overnight(today, evening));
    Overnight overnight = box[0];
    execution.putMetadata("usDate", overnight.usDate() == null ? null : overnight.usDate().toString());
    execution.putMetadata("triggers", overnight.triggers());
    if (overnight.lookaheadDropped() > 0) {
      execution.warn("오늘 이후 현지일 미국 행 " + overnight.lookaheadDropped() + "건을 입력에서 뺐습니다(룩어헤드 방어)");
    }

    // ② 프롬프트·스키마 → ③ 판단(필수, 매일 호출) → ④ 가드
    String payload = AdvisorJson.write(payload(evening, snapshot.get(), eveningPicks, candidates, overnight));
    List<String> pickTickers = eveningPicks.stream().map(PickRow::ticker).toList();
    List<String> candidateTickers = candidates.stream().map(CandidateRow::ticker).toList();
    List<String> addable = candidateTickers.stream().filter(t -> !pickTickers.contains(t)).toList();
    String schema = MorningAdviceSchemaFactory.schemaJson(pickTickers, addable, candidateTickers);
    execution.putMetadata("promptChars", payload.length());
    AtomicReference<MarketJudgeClient.CallResult<MorningAdviceResponse>> called = new AtomicReference<>();
    steps.runOrThrow("JUDGE", () -> called.set(judge.call(prompts.morningSystem(), payload, schema, MorningAdviceResponse.class)));
    MarketJudgeClient.CallResult<MorningAdviceResponse> jr = called.get();
    execution.recordLlmUsage(jr.model(), PromptResources.MORNING_VERSION, jr.usage(), jr.reasoningTokens(), jr.cachedTokens());
    MorningAdviceGuard.Result guarded = guard.validate(jr.value(), eveningPicks, candidates);
    Map<String, Object> guardJson = new LinkedHashMap<>(guarded.stats());
    if (!guarded.violations().isEmpty()) {
      guardJson.put("violations", guarded.violations());
    }
    execution.putMetadata("guard", guardJson);

    // 판단 중에 마감이 지났으면 저장하지 않는다 — "08:50 이후 생성된 MORNING 판단 0건" 을 불변식으로 유지한다(호출 비용은 이미 기록됨)
    late = pastDeadline(today);
    if (late.isPresent()) {
      promptInputs.upsert(new PromptInputRow(execution.runId(), AdviceVariant.LIVE, PromptResources.MORNING_VERSION, prompts.morningSha256(), payload,
          AdvisorJson.write(jr.options()), jr.rawText()));
      execution.skip("판단 도중 " + late.get());
      return;
    }

    // ⑤ 저장 (필수): 후보는 저녁 스냅샷 복사(같은 후보군 → 픽−후보군 비교가 저녁과 같은 모집단), 픽 = KEEP + ADD
    Map<String, Object> diff = diffJson(evening, guarded, overnight);
    Map<String, Object> dataAsOf = new LinkedHashMap<>(evening.dataAsOf() == null ? Map.of() : evening.dataAsOf());
    dataAsOf.put("overnightUs", overnight.usDate() == null ? null : overnight.usDate().toString());
    AdviceHeader header = AdviceHeader.builder()
        .runId(execution.runId()).baseDate(evening.baseDate()).adviceKind(AdviceKind.MORNING).variant(AdviceVariant.LIVE)
        .horizonDays(evening.horizonDays()).summary(guarded.summary())
        .trendKospi(evening.trendKospi()).trendKosdaq(evening.trendKosdaq()).trends(evening.trends())
        .dataAsOf(dataAsOf).entryDate(evening.entryDate()).exitDate(evening.exitDate()).newsIds(evening.newsIds())
        .promptVersion(PromptResources.MORNING_VERSION).model(jr.model()).systemFingerprint(jr.responseId())
        .weightSetId(evening.weightSetId()).activeLessonIds(List.of()).dataQuality(evening.dataQuality()).guard(guardJson)
        .parentAdviceId(evening.adviceId()).diffJson(diff)
        .build();
    final long[] adviceId = new long[1];
    steps.runOrThrow("SAVE", () -> {
      adviceId[0] = adviceWriter.insertHeader(header);
      adviceWriter.insertCandidates(adviceId[0], candidates);
      adviceWriter.insertPicks(adviceId[0], guarded.picks());
      promptInputs.upsert(new PromptInputRow(execution.runId(), AdviceVariant.LIVE, PromptResources.MORNING_VERSION, prompts.morningSha256(), payload,
          AdvisorJson.write(jr.options()), jr.rawText()));
    });
    execution.putMetadata("adviceId", adviceId[0]);
    execution.putMetadata("picks", guarded.picks().size());
    execution.putMetadata("keep", guarded.kept().size());
    execution.putMetadata("add", guarded.added().size());
    execution.putMetadata("drop", guarded.drops().size());

    // ⑥ 발행 (격리)
    Map<String, String> names = candidates.stream().filter(c -> c.stockName() != null)
        .collect(Collectors.toMap(CandidateRow::ticker, CandidateRow::stockName, (a, b) -> a));
    steps.run("PUBLISH", () -> {
      MorningAdviceMessage message = MorningAdviceMessage.builder().baseDate(evening.baseDate()).entryDate(evening.entryDate())
          .exitDate(evening.exitDate()).overnightLines(overnightLines(overnight)).kept(guarded.kept()).added(guarded.added()).drops(guarded.drops())
          .names(names).summary(guarded.summary()).triggered(Boolean.TRUE.equals(overnight.triggers().get("any"))).runId(execution.runId())
          .adviceId(adviceId[0]).parentAdviceId(evening.adviceId()).build();
      if (notifier.publish(message)) {
        adviceWriter.markPublished(adviceId[0], Instant.now());
      } else {
        execution.warn("아침 재판정 Slack 발행 실패 — 판단은 저장됨 (advice=" + adviceId[0] + ")");
      }
    });
  }

  /**
   * 발행 마감(advisor.morning.advise-deadline, 기본 08:50 KST) 이후면 사유. 기준일(execution.baseDate)의 마감 시각과 지금을 비교하므로
   * 과거 날짜를 수동 재실행하면 항상 마감 이후다 — 개장 후 발행은 D+1 시가 진입 규약과 모순이라 사후 보충도 하지 않는다.
   */
  Optional<String> pastDeadline(LocalDate today) {
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(MarketClock.KST);
    ZonedDateTime deadline = today.atTime(properties.getMorning().getAdviseDeadline()).atZone(MarketClock.KST);
    if (now.isBefore(deadline)) {
      return Optional.empty();
    }
    return Optional.of(String.format("발행 마감(%s KST) 이후 실행 — 개장 후 발행은 D+1 시가 진입과 모순 (now=%s)", properties.getMorning().getAdviseDeadline(),
        now.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS)));
  }

  /**
   * 밤사이 블록을 만든다. 미국은 현지일 &lt; today 만(GlobalLinkService SQL 이 이미 거르지만 한 번 더 — 수집 시계·재실행 오류가 섞여도 오늘 세션이 새지 않게),
   * β·σ 는 저녁 기준일 이하 국내 데이터로 추정한다. 저녁 기준일보다 오래된 미국 세션은 저녁이 이미 본 정보라 "새 정보 없음" 으로 다룬다.
   */
  Overnight overnight(LocalDate today, AdviceHeader evening) {
    LocalDate base = evening.baseDate();
    Map<String, String> primary = properties.getMorning().primarySymbols();
    LinkedHashSet<String> symbols = new LinkedHashSet<>(primary.values());
    for (String pair : properties.getMorning().getLinkPairs()) {
      String[] parts = pair.split(":", 2);
      if (parts.length == 2) {
        symbols.add(parts[1].trim());
      }
    }
    symbols.add(FX_SYMBOL);
    Map<String, GlobalLinkService.Overnight> fetched = links.overnight(new ArrayList<>(symbols), today);
    Map<String, GlobalLinkService.Overnight> us = new LinkedHashMap<>();
    int dropped = 0;
    for (String symbol : symbols) {
      GlobalLinkService.Overnight o = fetched.get(symbol);
      if (o == null || o.date() == null) {
        continue;
      }
      if (!o.date().isBefore(today)) {
        dropped++;
        continue;
      }
      us.put(symbol, o);
    }
    LocalDate usDate = primary.values().stream().map(us::get).filter(o -> o != null).map(GlobalLinkService.Overnight::date)
        .max(LocalDate::compareTo).orElse(null);
    // 저녁 기준일보다 오래된 세션(미국 휴장·수집 결측)은 저녁이 이미 본 정보 — 새 정보 없음으로 다룬다
    boolean usClosed = usDate == null || usDate.isBefore(base);

    Map<String, Object> json = new LinkedHashMap<>();
    json.put("usDate", usDate == null ? null : usDate.toString());
    json.put("usClosed", usClosed);
    Map<String, Object> usJson = new LinkedHashMap<>();
    for (Map.Entry<String, GlobalLinkService.Overnight> e : us.entrySet()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("date", e.getValue().date().toString());
      m.put("r1", round(e.getValue().r1()));
      if (FX_SYMBOL.equals(e.getKey())) {
        json.put("fx", m);
      } else {
        usJson.put(e.getKey(), m);
      }
    }
    json.put("us", usJson);

    // 지수별 예상 갭 = β(저녁 기준일까지) × 주 심볼 r1 — 미국 휴장이면 r1 을 쓰지 않는다(저녁이 이미 본 세션)
    List<Map<String, Object>> gaps = new ArrayList<>();
    List<String> gapTriggered = new ArrayList<>();
    for (String code : INDEX_CODES) {
      String symbol = primary.get(code);
      GlobalLink link = symbol == null ? null : links.link(code, symbol, base);
      Double beta = link == null ? null : link.beta();
      GlobalLinkService.Overnight o = symbol == null ? null : us.get(symbol);
      Double usR1 = o == null || usClosed ? null : o.r1();
      Double gapEst = beta == null || usR1 == null ? null : beta * usR1;
      Double sigma = links.sigma1d(code, base);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("index", code);
      m.put("symbol", symbol);
      m.put("beta", round(beta));
      m.put("usR1", round(usR1));
      m.put("gapEst", round(gapEst));
      m.put("sigma1d", round(sigma));
      gaps.add(m);
      if (gapEst != null && sigma != null && sigma > 0 && Math.abs(gapEst) >= sigma) {
        gapTriggered.add(code);
      }
    }
    json.put("gaps", gaps);

    // 섹터 연동 심볼: 저녁 기준일 이후 세션만(그 전 세션은 저녁이 이미 봤다), 오늘 이후 현지일은 룩어헤드라 뺀다
    List<Map<String, Object>> sectorJson = new ArrayList<>();
    List<String> sectorTriggered = new ArrayList<>();
    for (GlobalLinkService.SectorMove move : links.sectorMoves(today)) {
      if (move.date() == null || move.r1() == null) {
        continue;
      }
      if (!move.date().isBefore(today)) {
        dropped++;
        continue;
      }
      if (move.date().isBefore(base)) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("symbol", move.symbol());
      m.put("groups", move.groups());
      m.put("date", move.date().toString());
      m.put("r1", round(move.r1()));
      m.put("z", round2(move.z()));
      sectorJson.add(m);
      if (move.z() != null && Math.abs(move.z()) >= properties.getMorning().getSectorSigmaMultiple()) {
        sectorTriggered.add(move.symbol());
      }
    }
    json.put("sectorSymbols", sectorJson);

    // 07:30 규칙 점검 (없으면 생략 — 점검 잡이 꺼졌거나 늦어도 재판정은 돈다)
    Optional<MorningCheckRow> check = morningChecks.findByAdvice(evening.adviceId());
    check.ifPresent(c -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("verdict", c.verdict() == null ? null : c.verdict().getCode());
      m.put("gapKospi", round(c.gapKospi()));
      m.put("gapKosdaq", round(c.gapKosdaq()));
      json.put("morningCheck", m);
    });
    boolean caution = check.map(c -> c.verdict() == MorningVerdict.CAUTION).orElse(false);

    Map<String, Object> triggers = new LinkedHashMap<>();
    triggers.put("gap", !gapTriggered.isEmpty());
    triggers.put("gapIndexes", gapTriggered);
    triggers.put("sector", !sectorTriggered.isEmpty());
    triggers.put("sectorSymbols", sectorTriggered);
    triggers.put("caution", caution);
    triggers.put("morningCheck", check.isPresent());
    triggers.put("any", !gapTriggered.isEmpty() || !sectorTriggered.isEmpty() || caution);
    return new Overnight(json, triggers, usDate, usClosed, dropped);
  }

  /**
   * 사용자 메시지: 저녁 입력 원문(스냅샷) + 저녁 판단 요약 + 저녁 픽 + 밤사이 블록. 트리거 플래그는 싣지 않는다(사후 분석용 메타이지 판단 입력이 아니다).
   */
  static Map<String, Object> payload(AdviceHeader evening, PromptInputRow snapshot, List<PickRow> eveningPicks, List<CandidateRow> candidates,
      Overnight overnight) {
    Map<String, CandidateRow> byTicker = candidates.stream().collect(Collectors.toMap(CandidateRow::ticker, Function.identity(), (a, b) -> a));
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("baseDate", evening.baseDate().toString());
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("entry", evening.entryDate() == null ? null : evening.entryDate().toString());
    window.put("exit", evening.exitDate() == null ? null : evening.exitDate().toString());
    p.put("window", window);
    p.put("evening", AdvisorJson.readMap(snapshot.userPayload()));
    Map<String, Object> advice = new LinkedHashMap<>();
    advice.put("regime", evening.regimeCode() == null ? null : evening.regimeCode().getCode());
    advice.put("kospiDir", evening.kospiDir() == null ? null : evening.kospiDir().getCode());
    advice.put("kosdaqDir", evening.kosdaqDir() == null ? null : evening.kosdaqDir().getCode());
    advice.put("pUp", evening.pUp());
    advice.put("sectors", evening.leadingSectors() == null ? List.of() : evening.leadingSectors().stream().map(SectorCall::code).toList());
    advice.put("summary", evening.summary());
    p.put("eveningAdvice", advice);
    List<Map<String, Object>> picks = new ArrayList<>();
    for (PickRow pick : eveningPicks) {
      CandidateRow c = byTicker.get(pick.ticker());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("tkr", pick.ticker());
      m.put("name", c == null ? null : c.stockName());
      m.put("sec", c == null ? null : c.sectorCode());
      m.put("dir", pick.direction() == null ? null : pick.direction().getCode());
      m.put("conv", pick.conviction());
      m.put("thesis", pick.thesis());
      m.put("risk", pick.riskNote());
      picks.add(m);
    }
    p.put("eveningPicks", picks);
    p.put("overnight", overnight.json());
    return p;
  }

  /**
   * 헤더 diff_json: 저녁 대비 조치 목록과 트리거 메타. DROP 은 MORNING 픽에 없으므로 여기가 유일한 기록이다(채팅 compareAdvice 가 사유를 읽는다).
   */
  static Map<String, Object> diffJson(AdviceHeader evening, MorningAdviceGuard.Result guarded, Overnight overnight) {
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("parentAdviceId", evening.adviceId());
    diff.put("keep", guarded.kept().stream().map(p -> action(p.ticker(), p.actionReason())).toList());
    diff.put("add", guarded.added().stream().map(p -> action(p.ticker(), p.actionReason())).toList());
    List<Map<String, Object>> drops = new ArrayList<>();
    for (MorningAdviceGuard.Drop d : guarded.drops()) {
      Map<String, Object> m = action(d.evening().ticker(), d.reason());
      m.put("direction", d.evening().direction() == null ? null : d.evening().direction().getCode());
      m.put("conviction", d.evening().conviction());
      drops.add(m);
    }
    diff.put("drop", drops);
    diff.put("triggers", overnight.triggers());
    diff.put("usDate", overnight.usDate() == null ? null : overnight.usDate().toString());
    diff.put("usClosed", overnight.usClosed());
    return diff;
  }

  private static Map<String, Object> action(String ticker, String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ticker", ticker);
    m.put("reason", reason);
    return m;
  }

  /**
   * Slack 밤사이 요약 줄: 미국 지수·환율 한 줄 + 지수별 예상 갭 + 트리거 임계(sector-sigma-multiple) 이상 움직인 섹터 연동 심볼 + 07:30 점검 판정.
   */
  @SuppressWarnings("unchecked")
  static List<String> overnightLines(Overnight overnight) {
    Map<String, Object> json = overnight.json();
    List<String> lines = new ArrayList<>();
    List<String> parts = new ArrayList<>();
    ((Map<String, Object>) json.getOrDefault("us", Map.of())).forEach((symbol, v) -> {
      Object r1 = ((Map<String, Object>) v).get("r1");
      if (r1 instanceof Number n) {
        parts.add(String.format("%s %+.1f%%", symbol, n.doubleValue() * 100));
      }
    });
    Object fx = json.get("fx");
    if (fx instanceof Map<?, ?> fxMap && fxMap.get("r1") instanceof Number n) {
      parts.add(String.format("환율 %+.1f%%", n.doubleValue() * 100));
    }
    String head = overnight.usClosed() ? "미국 새 세션 없음(휴장·결측)" : "미국 " + overnight.usDate() + " 마감";
    lines.add(head + (parts.isEmpty() ? "" : ": " + String.join(" · ", parts)));
    for (Map<String, Object> g : (List<Map<String, Object>>) json.getOrDefault("gaps", List.of())) {
      if (g.get("gapEst") instanceof Number gap) {
        Object sigma = g.get("sigma1d");
        lines.add(String.format("%s 예상 갭 %+.2f%% (σ %s)", "0001".equals(g.get("index")) ? "KOSPI" : "KOSDAQ", gap.doubleValue() * 100,
            sigma instanceof Number s ? String.format("%.2f%%", s.doubleValue() * 100) : "-"));
      }
    }
    List<String> movers = new ArrayList<>();
    List<String> flagged = (List<String>) overnight.triggers().getOrDefault("sectorSymbols", List.of());
    for (Map<String, Object> s : (List<Map<String, Object>>) json.getOrDefault("sectorSymbols", List.of())) {
      if (flagged.contains(s.get("symbol")) && s.get("z") instanceof Number z && s.get("r1") instanceof Number r1) {
        movers.add(String.format("%s(%s) %+.1f%% z%+.1f", s.get("symbol"), s.get("groups"), r1.doubleValue() * 100, z.doubleValue()));
      }
    }
    if (!movers.isEmpty()) {
      lines.add("섹터 연동 급변: " + String.join(" · ", movers));
    }
    if (json.get("morningCheck") instanceof Map<?, ?> check) {
      lines.add("07:30 점검: " + check.get("verdict"));
    }
    return lines;
  }

  private static Double round(Double v) {
    return v == null ? null : Math.round(v * 1e6) / 1e6;
  }

  private static Double round2(Double v) {
    return v == null ? null : Math.round(v * 100) / 100.0;
  }
}
