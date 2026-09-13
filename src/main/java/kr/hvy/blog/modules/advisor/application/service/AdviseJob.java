package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.DailyAdviceMessage;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.NewsBlock;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import kr.hvy.blog.modules.advisor.domain.model.ScreeningResult;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.LessonRepository;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 일일 판단 파이프라인 (ADVISE, 평일 19:30~19:55 KST).
 * <pre>
 * 게이트 → 채점·IC 증분(격리) → 시장 특징 → 스크리닝(활성 가중치) → QUANT_TOPN 섀도 저장 → 프롬프트(실적 블록·교훈은 표본 게이트 뒤) →
 * LLM 판단(strict 스키마) → 가드 → LIVE 저장·입력 스냅샷 → Slack 발행 → (메모리 활성 시) LLM_NOMEM 섀도
 * </pre>
 * 스크리닝·판단·저장은 실패하면 잡 전체가 FAILED(부분 추천 금지). 채점·섀도·발행 실패는 격리되어 PARTIAL 로 남는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdviseJob implements AdvisorJob {

  private final AdvisorProperties properties;
  private final AdvisorGateService gate;
  private final SignalIcService icService;
  private final MarketFeatureService marketFeatures;
  private final CandidateScreeningService screening;
  private final WeightSetRepository weightSets;
  private final AdvicePromptBuilder promptBuilder;
  private final PromptResources prompts;
  private final MarketJudgeClient judge;
  private final AdviceGuard guard;
  private final AdviceWriter adviceWriter;
  private final PromptInputWriter promptInputs;
  private final LessonRepository lessons;
  private final AdvisorNotifier notifier;
  private final ObjectProvider<ScoreHook> scoreHook;
  /** 뉴스 입력(advice-v4). advisor.news.enabled 일 때만 쓴다 */
  private final ObjectProvider<NewsFeatureService> newsFeatures;

  /** 채점 단계 훅 (Phase 5 의 ScoreJob 이 구현). 없으면 IC 증분만 돈다 */
  public interface ScoreHook {

    /** 전일·5일 전·20일 전 판단을 채점하고 스코어보드 줄(Slack)·블록(프롬프트)을 돌려준다 */
    Scoreboard scoreDue(AdvisorExecution execution);
  }

  /** 스코어보드: Slack 줄 + 프롬프트 블록(표본 게이트를 넘겼을 때만 non-null) */
  public record Scoreboard(List<String> slackLines, Map<String, Object> promptBlock) {

    public static Scoreboard empty() {
      return new Scoreboard(List.of(), null);
    }
  }

  /**
   * 유일한 생성자. 판단 클라이언트는 AdvisorAiConfig 의 judgeClient 빈을 받는다.
   * 생성자를 둘(Spring 용·테스트 용) 두면 @Autowired 없는 Spring 은 기본 생성자로 후퇴해 기동이 실패하므로(2026-09-13) 하나만 유지한다.
   */
  public AdviseJob(AdvisorProperties properties, AdvisorGateService gate, SignalIcService icService, MarketFeatureService marketFeatures,
      CandidateScreeningService screening, WeightSetRepository weightSets, AdvicePromptBuilder promptBuilder, PromptResources prompts,
      @Qualifier(MarketJudgeClient.JUDGE_BEAN) MarketJudgeClient judge, AdviceWriter adviceWriter, PromptInputWriter promptInputs,
      LessonRepository lessons, AdvisorNotifier notifier, ObjectProvider<ScoreHook> scoreHook, ObjectProvider<NewsFeatureService> newsFeatures) {
    this.properties = properties;
    this.gate = gate;
    this.icService = icService;
    this.marketFeatures = marketFeatures;
    this.screening = screening;
    this.weightSets = weightSets;
    this.promptBuilder = promptBuilder;
    this.prompts = prompts;
    this.judge = judge;
    this.guard = new AdviceGuard(properties);
    this.adviceWriter = adviceWriter;
    this.promptInputs = promptInputs;
    this.lessons = lessons;
    this.notifier = notifier;
    this.scoreHook = scoreHook;
    this.newsFeatures = newsFeatures;
  }

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.ADVISE;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    LocalDate baseDate = execution.baseDate();
    AdvisorGateService.Decision decision = gate.decide(baseDate, LocalTime.now(MarketClock.KST));
    if (!decision.ready()) {
      execution.skip(decision.reason());
      if (decision.pastDeadline()) {
        notifier.alert(String.format("[AI 판단 미실행] %s — %s%n마감(%s KST)까지 DAILY 수집이 끝나지 않아 오늘 판단을 건너뜁니다. "
                + "수집 복구 후 POST /api/advisor/admin/jobs/ADVISE?baseDate=%s 로 보충",
            baseDate, decision.reason(), properties.getAdvise().getDeadline(), baseDate), true);
      }
      return;
    }
    execution.putMetadata("dataQuality", decision.quality().getCode());
    AdvisorSteps steps = new AdvisorSteps(execution);

    // ① 채점·IC (격리 — 실패해도 오늘 판단은 진행, 직전 가중치·실적으로)
    final Scoreboard[] scoreboard = {Scoreboard.empty()};
    ScoreHook hook = scoreHook.getIfAvailable();
    if (hook != null) {
      steps.run("SCORE", () -> scoreboard[0] = hook.scoreDue(execution));
    } else {
      steps.skip("SCORE", "채점 훅 없음");
    }
    // IC 청크는 steps::run 으로 돌아 청크마다 단계 기록·flush·취소 감지를 받는다. 바깥 IC 단계는 청크 밖 조회(마지막 IC 일·캘린더) 실패를 격리한다
    steps.run("IC", () -> icService.computeIncremental(steps::run).ifPresent(r -> r.record(execution)));

    // ② 시장 특징·스크리닝 (필수)
    final MarketFeatures[] market = new MarketFeatures[1];
    final ScreeningResult[] screened = new ScreeningResult[1];
    steps.runOrThrow("FEATURES", () -> market[0] = marketFeatures.features(baseDate));
    steps.runOrThrow("SCREEN", () -> screened[0] = screening.screen(baseDate));
    ScreeningResult result = screened[0];
    if (result.candidates().size() < properties.getPickMin()) {
      throw new IllegalStateException("후보가 너무 적습니다: " + result.candidates().size() + " (universe " + result.universeSize() + ")");
    }
    execution.putMetadata("markets", properties.getMarkets());
    execution.putMetadata("universe", result.universeSize());
    execution.putMetadata("cut", result.cutSize());
    execution.putMetadata("candidates", result.candidates().size());
    execution.putMetadata("weightSetId", result.weightSetId());
    WeightSet weightSet = weightSets.find(result.weightSetId()).orElseThrow();

    // ③ 정량 top-N 섀도 (LLM 없음) — 격리
    steps.run("SHADOW_QUANT", () -> saveQuantShadow(execution, result, decision, market[0]));

    // ④ 프롬프트: 실적 블록·교훈은 누적 픽 게이트를 넘긴 뒤에만
    boolean memoryOn = adviceWriter.countLivePicks() >= properties.getLesson().getMinPicks();
    final List<LessonRow> activeLessons = memoryOn ? activeLessons() : List.of();
    // 교훈의 regime 조건은 오늘 국면을 모르는 시점이라 직전 LIVE 판단의 국면으로 평가한다. trend 조건은 규칙이 기준일에 확정한 오늘 값으로 즉시 판정한다
    MarketRegimeCode previousRegime = adviceWriter.findLatest(AdviceVariant.LIVE, baseDate.minusDays(1))
        .map(AdviceHeader::regimeCode).orElse(null);
    Map<String, MarketTrendCode> trendCodes = market[0].trendCodes();
    List<CandidateRow> candidates = tagLessons(result.candidates(), activeLessons, previousRegime, trendCodes);
    ScreeningResult tagged = new ScreeningResult(result.baseDate(), result.universeSize(), result.cutSize(), result.weightSetId(), candidates);
    Map<String, Object> promptScoreboard = memoryOn ? scoreboard[0].promptBlock() : null;
    // 뉴스(advice-v4): 켜져 있으면 판단 시각 이전 창의 헤드라인. 조회 실패는 격리 — 뉴스 없이 판단한다
    final NewsBlock[] newsBox = new NewsBlock[1];
    if (properties.getNews().isEnabled()) {
      List<String> candidateTickers = candidates.stream().map(CandidateRow::ticker).toList();
      steps.run("NEWS", () -> newsBox[0] = newsFeatures.getIfAvailable() == null ? null
          : newsFeatures.getIfAvailable().news(baseDate, candidateTickers).orElse(null));
    } else {
      steps.skip("NEWS", "뉴스 입력 비활성");
    }
    final NewsBlock news = newsBox[0];
    PromptPayload payload = promptBuilder.build(market[0], tagged, promptScoreboard, activeLessons, weightSet.enabledWeights(), decision.quality(), news);
    if (payload.truncated()) {
      execution.warn("입력 길이 상한으로 후보를 " + payload.candidatesIncluded() + "개로 줄였습니다");
    }
    execution.putMetadata("promptChars", payload.json().length());
    execution.putMetadata("memoryOn", memoryOn);
    execution.putMetadata("newsIds", payload.newsIds().size());
    Map<String, String> sectorNames = sectorNames(market[0], candidates);
    String schema = AdviceSchemaFactory.schemaJson(payload.candidateTickers(), payload.sectorCodes(), payload.newsIds());

    // ⑤ 판단 (필수) → ⑥ 가드
    final MarketJudgeClient.JudgeResult[] judged = new MarketJudgeClient.JudgeResult[1];
    steps.runOrThrow("JUDGE", () -> judged[0] = judge.judge(prompts.adviceSystem(), payload, schema));
    MarketJudgeClient.JudgeResult jr = judged[0];
    execution.recordLlmUsage(jr.model(), PromptResources.ADVICE_VERSION, jr.usage(), jr.reasoningTokens(), jr.cachedTokens());
    List<CandidateRow> included = candidates.subList(0, payload.candidatesIncluded());
    AdviceGuard.Result guarded = guard.validate(jr.response(), included, sectorNames, trendCodes, news);
    execution.putMetadata("guard", guarded.stats());
    if (guarded.tooFew(properties.getPickMin())) {
      promptInputs.upsert(new PromptInputRow(execution.runId(), AdviceVariant.LIVE, PromptResources.ADVICE_VERSION, prompts.adviceSha256(),
          payload.json(), AdvisorJson.write(jr.options()), jr.rawText()));
      throw new IllegalStateException("가드 통과 픽이 " + guarded.picks().size() + "개로 최소 " + properties.getPickMin() + " 미만 — 발행하지 않습니다");
    }
    if (guarded.removalRatio() > AdviceGuard.REMOVAL_ALERT_RATIO) {
      execution.markPartial(String.format("가드 제거율 %.0f%% (%d/%d) — 모델·프롬프트 점검 필요", guarded.removalRatio() * 100, guarded.removed(),
          guarded.originalPicks()));
    }

    // ⑦ LIVE 저장 (필수)
    AdviceHeader header = AdviceHeader.builder()
        .runId(execution.runId()).baseDate(baseDate).adviceKind(AdviceHeader.KIND_DAILY).variant(AdviceVariant.LIVE)
        .horizonDays(properties.getHorizonDays())
        .regimeCode(guarded.regime()).kospiDir(guarded.kospiDir()).kosdaqDir(guarded.kosdaqDir()).pUp(guarded.pUp())
        .regimeRationale(guarded.rationale()).leadingSectors(guarded.sectors()).summary(guarded.summary())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).trends(market[0].trends()).outlooks(guarded.outlooks())
        .dataAsOf(market[0].dataAsOf()).entryDate(market[0].entryDate()).exitDate(market[0].exitDate()).newsIds(payload.newsIds())
        .promptVersion(PromptResources.ADVICE_VERSION).model(jr.model()).systemFingerprint(jr.responseId())
        .weightSetId(result.weightSetId()).activeLessonIds(activeLessons.stream().map(LessonRow::lessonId).toList())
        .dataQuality(decision.quality()).guard(guarded.stats())
        .build();
    final long[] adviceId = new long[1];
    steps.runOrThrow("SAVE", () -> {
      adviceId[0] = adviceWriter.insertHeader(header);
      adviceWriter.insertCandidates(adviceId[0], included);
      adviceWriter.insertPicks(adviceId[0], guarded.picks());
      promptInputs.upsert(new PromptInputRow(execution.runId(), AdviceVariant.LIVE, PromptResources.ADVICE_VERSION, prompts.adviceSha256(),
          payload.json(), AdvisorJson.write(jr.options()), jr.rawText()));
      for (LessonRow lesson : activeLessons) {
        int applied = (int) included.stream().filter(c -> c.appliedLessonIds() != null && c.appliedLessonIds().contains(lesson.lessonId())).count();
        lessons.addApplied(lesson.lessonId(), applied);
      }
    });
    execution.putMetadata("adviceId", adviceId[0]);
    execution.putMetadata("picks", guarded.picks().size());

    // ⑧ 발행 (격리)
    Map<String, CandidateRow> byTicker = included.stream().collect(Collectors.toMap(CandidateRow::ticker, c -> c, (a, b) -> a, LinkedHashMap::new));
    steps.run("PUBLISH", () -> {
      DailyAdviceMessage message = DailyAdviceMessage.builder()
          .header(header.toBuilder().adviceId(adviceId[0]).build()).picks(guarded.picks()).candidates(byTicker)
          .marketLabel(String.join("·", properties.getMarkets()))
          .scoreboardLines(scoreboard[0].slackLines()).runId(execution.runId())
          .promptTokens(execution.promptTokens()).completionTokens(execution.completionTokens())
          .costText(execution.costUsd().signum() > 0 ? "$" + execution.costUsd().setScale(4, java.math.RoundingMode.HALF_UP) : null)
          .degraded(decision.quality() != kr.hvy.blog.modules.advisor.domain.code.DataQuality.OK)
          .build();
      if (notifier.publish(message)) {
        adviceWriter.markPublished(adviceId[0], Instant.now());
      } else {
        execution.warn("Slack 발행 실패 — 판단은 저장됨 (advice=" + adviceId[0] + ")");
      }
    });

    // ⑨ 섀도 (격리). 요인 분리: NOMEM = 뉴스 그대로·메모리 없음, NONEWS = 메모리 그대로·뉴스 없음 — LIVE 와 정확히 한 요인만 다르다
    if (memoryOn && (!activeLessons.isEmpty() || promptScoreboard != null)) {
      steps.run("SHADOW_NOMEM", () -> saveLlmShadow(AdviceVariant.LLM_NOMEM, execution, market[0], tagged, weightSet, sectorNames, decision,
          null, List.of(), news, "shadowNomemAdviceId"));
    } else {
      steps.skip("SHADOW_NOMEM", "메모리 미활성");
    }
    if (news != null && nonewsShadowOpen(baseDate)) {
      steps.run("SHADOW_NONEWS", () -> saveLlmShadow(AdviceVariant.LLM_NONEWS, execution, market[0], tagged, weightSet, sectorNames, decision,
          promptScoreboard, activeLessons, null, "shadowNonewsAdviceId"));
    } else {
      steps.skip("SHADOW_NONEWS", news == null ? "뉴스 없음" : "뉴스 섀도 기간 종료");
    }
  }

  /**
   * 뉴스 없는 섀도를 돌릴 기간인지: 뉴스가 실린 첫 LIVE 판단부터 advisor.shadow.nonews-weeks 주 안. 첫 판단이 아직 없으면(오늘이 처음) 연다.
   */
  boolean nonewsShadowOpen(LocalDate baseDate) {
    return adviceWriter.firstNewsAdviceDate().map(first -> !baseDate.isAfter(first.plusWeeks(properties.getShadow().getNonewsWeeks()))).orElse(true);
  }

  /**
   * 프롬프트에 넣을 활성 교훈 (최신순, 상한 advisor.lesson.active-limit).
   */
  private List<LessonRow> activeLessons() {
    List<LessonRow> active = lessons.findByStatus(LessonStatus.ACTIVE);
    int limit = properties.getLesson().getActiveLimit();
    return active.size() > limit ? List.copyOf(active.subList(0, limit)) : active;
  }

  /**
   * 정량 top-N 동일가중 섀도: LLM 없이 점수 상위 N 을 LONG·확신 0.55 로 저장 (LLM 부가가치의 대조군).
   */
  private void saveQuantShadow(AdvisorExecution execution, ScreeningResult result, AdvisorGateService.Decision decision, MarketFeatures market) {
    if (adviceWriter.find(result.baseDate(), AdviceHeader.KIND_DAILY, AdviceVariant.QUANT_TOPN).isPresent()) {
      return;
    }
    int n = Math.min(properties.getShadow().getQuantTopN(), result.candidates().size());
    Map<String, MarketTrendCode> trendCodes = market.trendCodes();
    AdviceHeader header = AdviceHeader.builder().runId(execution.runId()).baseDate(result.baseDate()).adviceKind(AdviceHeader.KIND_DAILY)
        .variant(AdviceVariant.QUANT_TOPN).horizonDays(properties.getHorizonDays()).weightSetId(result.weightSetId())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).entryDate(market.entryDate()).exitDate(market.exitDate())
        .dataQuality(decision.quality()).promptVersion("quant").model("quant-top-" + n).build();
    long id = adviceWriter.insertHeader(header);
    adviceWriter.insertCandidates(id, result.candidates());
    List<PickRow> picks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      picks.add(PickRow.builder().ticker(result.candidates().get(i).ticker()).pickRank(i + 1).direction(PickDirection.LONG).conviction(0.55).build());
    }
    adviceWriter.insertPicks(id, picks);
    execution.putMetadata("shadowQuantAdviceId", id);
  }

  /**
   * LLM 섀도 1회: 같은 시장·후보 입력에서 요인 하나만 바꿔 한 번 더 판단해 variant 로 저장한다 (발행 없음).
   * LLM_NOMEM 은 메모리(실적 블록·교훈) 없이·뉴스는 그대로, LLM_NONEWS 는 뉴스 없이·메모리는 그대로 — 각 섀도가 LIVE 와 정확히 한 요인만 달라야
   * (LIVE − 섀도) 가 그 요인의 가치가 된다.
   */
  private void saveLlmShadow(AdviceVariant variant, AdvisorExecution execution, MarketFeatures market, ScreeningResult screened, WeightSet weightSet,
      Map<String, String> sectorNames, AdvisorGateService.Decision decision, Map<String, Object> scoreboard, List<LessonRow> lessons, NewsBlock news,
      String metadataKey) {
    if (adviceWriter.find(screened.baseDate(), AdviceHeader.KIND_DAILY, variant).isPresent()) {
      return;
    }
    PromptPayload payload = promptBuilder.build(market, screened, scoreboard, lessons, weightSet.enabledWeights(), decision.quality(), news);
    String schema = AdviceSchemaFactory.schemaJson(payload.candidateTickers(), payload.sectorCodes(), payload.newsIds());
    MarketJudgeClient.JudgeResult jr = judge.judge(prompts.adviceSystem(), payload, schema);
    execution.recordLlmUsage(jr.model(), PromptResources.ADVICE_VERSION, jr.usage(), jr.reasoningTokens(), jr.cachedTokens());
    List<CandidateRow> included = screened.candidates().subList(0, payload.candidatesIncluded());
    Map<String, MarketTrendCode> trendCodes = market.trendCodes();
    AdviceGuard.Result guarded = guard.validate(jr.response(), included, sectorNames, trendCodes, news);
    AdviceHeader header = AdviceHeader.builder().runId(execution.runId()).baseDate(screened.baseDate()).adviceKind(AdviceHeader.KIND_DAILY)
        .variant(variant).horizonDays(properties.getHorizonDays())
        .regimeCode(guarded.regime()).kospiDir(guarded.kospiDir()).kosdaqDir(guarded.kosdaqDir()).pUp(guarded.pUp())
        .regimeRationale(guarded.rationale()).leadingSectors(guarded.sectors()).summary(guarded.summary())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).trends(market.trends()).outlooks(guarded.outlooks())
        .dataAsOf(market.dataAsOf()).entryDate(market.entryDate()).exitDate(market.exitDate()).newsIds(payload.newsIds())
        .promptVersion(PromptResources.ADVICE_VERSION).model(jr.model()).systemFingerprint(jr.responseId())
        .weightSetId(screened.weightSetId()).activeLessonIds(lessons.stream().map(LessonRow::lessonId).toList())
        .dataQuality(decision.quality()).guard(guarded.stats()).build();
    long id = adviceWriter.insertHeader(header);
    adviceWriter.insertCandidates(id, included);
    adviceWriter.insertPicks(id, guarded.picks());
    promptInputs.upsert(new PromptInputRow(execution.runId(), variant, PromptResources.ADVICE_VERSION, prompts.adviceSha256(),
        payload.json(), AdvisorJson.write(jr.options()), jr.rawText()));
    execution.putMetadata(metadataKey, id);
  }

  /**
   * 활성 교훈의 condition 을 후보마다 평가해 appliedLessonIds 를 태깅한다 (효과 상대 비교의 근거).
   * regime 조건은 직전 LIVE 국면(어제 값), trend 조건은 후보 소속 시장의 오늘 규칙 추세로 판정한다.
   */
  static List<CandidateRow> tagLessons(List<CandidateRow> candidates, List<LessonRow> activeLessons, MarketRegimeCode regime,
      Map<String, MarketTrendCode> trends) {
    if (activeLessons.isEmpty()) {
      return candidates;
    }
    List<CandidateRow> tagged = new ArrayList<>();
    for (CandidateRow c : candidates) {
      MarketTrendCode trend = trends == null || c.benchIndexCode() == null ? null : trends.get(c.benchIndexCode());
      List<Long> ids = activeLessons.stream().filter(l -> LessonCondition.matches(l.condition(), c, regime, trend)).map(LessonRow::lessonId).toList();
      tagged.add(c.toBuilder().appliedLessonIds(ids).build());
    }
    return tagged;
  }

  private static Map<String, String> sectorNames(MarketFeatures market, List<CandidateRow> candidates) {
    Map<String, String> names = new LinkedHashMap<>();
    market.topSectors().forEach(s -> names.put(s.code(), s.name()));
    market.bottomSectors().forEach(s -> names.put(s.code(), s.name()));
    candidates.stream().filter(c -> c.sectorCode() != null).forEach(c -> names.putIfAbsent(c.sectorCode(), c.sectorName()));
    return names;
  }

  /** 테스트용 */
  Optional<ScoreHook> hook() {
    return Optional.ofNullable(scoreHook.getIfAvailable());
  }
}
