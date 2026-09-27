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
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickUniverse;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
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
 * 판단 파이프라인 (ADVISE 평일 19:30~19:55 KST · ADVISE_ADHOC 채팅 수시 · ADVISE_H20 금요일 20:10, 종류별 차이는 {@link KindPlan}).
 * <pre>
 * 게이트 → 채점·IC 증분(격리) → 시장 특징(합성 국면·테마 포함) → 스크리닝(활성 가중치, 픽 유니버스) → QUANT_TOPN·QUANT_TOPN_BROAD 섀도 저장 → 프롬프트(실적 블록·교훈은 표본 게이트 뒤,
 * recentOutcomes 확정 빈도표는 확정 노트 게이트 뒤 — 격리) → LLM 판단(strict 스키마) → 가드(정책 표 한도) → LIVE 저장(regime_json)(memory_json)·입력 스냅샷 → Slack 발행 →
 * (메모리가 하나라도 실렸고 nomem-weeks 창 안이면) LLM_NOMEM 섀도 → (뉴스가 실렸고 nonews-weeks 창 안이면) LLM_NONEWS 섀도
 * </pre>
 * 스크리닝·판단·저장은 실패하면 잡 전체가 FAILED(부분 추천 금지). 채점·노트·섀도·발행 실패는 격리되어 PARTIAL 로 남는다.
 * 매수 전용(advice-v9·advice-h20-v2, 2026-09-27): 가드 뒤 0픽은 관망이며 픽 0행 헤더로 저장·발행한다(FAILED 아님, 메타 abstain=true).
 * <p>
 * 2계층 메모리(note-v1, 2026-09-21): 빠른 층 = recentOutcomes(12:00 노트의 T+5 확정 빈도표, 300 게이트 전에도 주입), 느린 층 = scoreboard·lessons(300 게이트 뒤).
 * LLM_NOMEM 은 세 가지 전부 없는 판단이라 300 전에는 정확히 "노트만 뺀" 1요인 섀도가 된다. 헤더 memory_json 이 어떤 메모리가 실렸는지 남기고 NOMEM 창의 시작점이 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdviseJob implements AdvisorJob {

  /** 수시 판단(ADHOC)이 건너뛰는 단계의 사유 */
  static final String ADHOC_SKIP = "수시 판단(ADHOC)은 학습·평가 루프 밖";
  /** 주간 20거래일 판단(H20)이 건너뛰는 단계의 사유 — 입력은 추세·섹터·테마·국면만, 섀도는 규칙형 QUANT_TOPN 1개 */
  static final String H20_SKIP = "H20 은 추세·섹터·테마·국면 입력만(뉴스·메모리 없음) — 채점·IC 는 DAILY ADVISE 몫, 섀도는 QUANT_TOPN 만";

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
  /** 픽 범위가 다른 종류(H20)의 정책 표 재계산용 */
  private final RegimePolicy regimePolicy;
  private final AdviceWriter adviceWriter;
  private final PromptInputWriter promptInputs;
  private final LessonRepository lessons;
  private final AdvisorNotifier notifier;
  private final ObjectProvider<ScoreHook> scoreHook;
  /** 뉴스 입력(advice-v4). advisor.news.enabled 일 때만 쓴다 */
  private final ObjectProvider<NewsFeatureService> newsFeatures;
  /** 12:00 노트의 T+5 확정 빈도표(note-v1) */
  private final RecentOutcomesService recentOutcomes;

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
      LessonRepository lessons, AdvisorNotifier notifier, ObjectProvider<ScoreHook> scoreHook, ObjectProvider<NewsFeatureService> newsFeatures,
      RecentOutcomesService recentOutcomes) {
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
    this.regimePolicy = new RegimePolicy(properties);
    this.adviceWriter = adviceWriter;
    this.promptInputs = promptInputs;
    this.lessons = lessons;
    this.notifier = notifier;
    this.scoreHook = scoreHook;
    this.newsFeatures = newsFeatures;
    this.recentOutcomes = recentOutcomes;
  }

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.ADVISE;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    advise(execution, AdviceKind.DAILY);
  }

  /**
   * 판단 종류별 파이프라인 구성(M7). advise 본문에 종류 분기를 흩지 않고 여기서 한 번에 정한다 — 새 종류는 {@link #plan} 에 행 하나를 더한다.
   *
   * @param horizonDays  결정 호라이즌(거래일) — 적용 구간·프롬프트 horizonDays·헤더 horizon_days
   * @param bounds       픽 개수 범위 — min 은 스크리닝 후보 수 최소, max 는 가드 절단·정책 표 LONG 상한(0픽 관망은 정상)
   * @param learningLoop 채점·IC·QUANT_TOPN_BROAD·LLM 섀도·교훈 적용 카운트(학습·평가 루프). DAILY 만
   * @param quantShadow  규칙형 QUANT_TOPN 섀도(비용 0, LLM 부가가치의 대조군). DAILY·H20
   * @param memory       느린 층(실적 블록·교훈)·빠른 층(recentOutcomes) 메모리. DAILY·ADHOC
   * @param news         뉴스 블록(advisor.news.enabled 일 때). DAILY·ADHOC
   * @param skipReason   이 종류가 건너뛰는 단계에 남기는 사유
   */
  record KindPlan(AdviceKind kind, int horizonDays, AdviceGuard.PickBounds bounds, boolean learningLoop, boolean quantShadow, boolean memory,
                  boolean news, String skipReason) {
  }

  /**
   * 종류 → 파이프라인 구성. ADHOC 은 DAILY 와 같은 입력(메모리·뉴스)으로 LIVE 1건만, H20 은 뉴스·메모리 없이 H20 가중치·20거래일 창으로 LIVE + QUANT_TOPN 섀도만 만든다.
   */
  KindPlan plan(AdviceKind kind) {
    return switch (kind) {
      case DAILY -> new KindPlan(kind, properties.getHorizonDays(), AdviceGuard.PickBounds.of(properties), true, true, true, true, null);
      case ADHOC -> new KindPlan(kind, properties.getHorizonDays(), AdviceGuard.PickBounds.of(properties), false, false, true, true, ADHOC_SKIP);
      case H20 -> new KindPlan(kind, properties.decisionHorizon(AdviceKind.H20),
          new AdviceGuard.PickBounds(properties.getH20().getPickMin(), properties.getH20().getPickMax()), false, true, false, false, H20_SKIP);
      default -> throw new IllegalArgumentException("AdviseJob 이 만들지 않는 판단 종류입니다: " + kind);
    };
  }

  /**
   * 판단 파이프라인 본문. 종류별 차이는 {@link KindPlan} 이 정한다.
   * <ul>
   *   <li>ADHOC(채팅 수시 판단, {@link AdhocAdviseJob}): 같은 입력·프롬프트·가드로 LIVE 1건만 — 학습·평가 루프를 건드리는 단계는 전부 건너뛴다(KPI·300 게이트·IC 밖)</li>
   *   <li>H20(주간 20거래일, {@link H20AdviseJob}): H20 활성 가중치 세트가 없으면 SKIP(DAILY 세트 폴백 금지), 뉴스·메모리 없이 advice-h20-v2 로 판단하고
   *       정책 표를 H20 픽 상한으로 다시 계산해 강제한다. 섀도는 규칙형 QUANT_TOPN 1개(LLM 섀도 없음)</li>
   * </ul>
   */
  void advise(AdvisorExecution execution, AdviceKind kind) {
    KindPlan plan = plan(kind);
    LocalDate baseDate = execution.baseDate();
    execution.putMetadata("adviceKind", kind.getCode());
    execution.putMetadata("horizonDays", plan.horizonDays());
    AdvisorGateService.Decision decision = gate.decide(baseDate, LocalTime.now(MarketClock.KST));
    if (kind != AdviceKind.DAILY) {
      Optional<String> blocked = offCycleBlocked(baseDate, kind, decision);
      if (blocked.isPresent()) {
        execution.skip(blocked.get());
        return;
      }
    } else if (!decision.ready()) {
      execution.skip(decision.reason());
      if (decision.pastDeadline()) {
        notifier.alert(String.format("[AI 판단 미실행] %s — %s%n마감(%s KST)까지 DAILY 수집이 끝나지 않아 오늘 판단을 건너뜁니다. "
                + "수집 복구 후 POST /api/advisor/admin/jobs/ADVISE?baseDate=%s 로 보충",
            baseDate, decision.reason(), properties.getAdvise().getDeadline(), baseDate), true);
      }
      return;
    }
    // DAILY 와 다른 호라이즌(H20)은 그 호라이즌의 활성 가중치 세트가 있어야 한다 — 5일 세트로 폴백하면 H20 성과를 DAILY 점수와 분리해 잴 수 없다
    boolean ownHorizon = plan.horizonDays() != properties.getHorizonDays();
    if (ownHorizon && screening.weightSetFor(kind).isEmpty()) {
      String reason = String.format("%s 활성 가중치 세트(h=%d)가 없습니다 — IC_BACKFILL?horizon=%d 뒤 WEEKLY_REVIEW 가 세트를 만들어야 발행합니다",
          kind.getCode(), plan.horizonDays(), plan.horizonDays());
      execution.putMetadata("skip.WEIGHTS", reason);
      execution.skip(reason);
      return;
    }
    execution.putMetadata("dataQuality", decision.quality().getCode());
    AdvisorSteps steps = new AdvisorSteps(execution);

    // ① 채점·IC (격리 — 실패해도 오늘 판단은 진행, 직전 가중치·실적으로). DAILY 만: 다른 종류의 채점·IC 는 매일 도는 DAILY ADVISE 가 함께 한다
    final Scoreboard[] scoreboard = {Scoreboard.empty()};
    ScoreHook hook = scoreHook.getIfAvailable();
    if (!plan.learningLoop()) {
      steps.skip("SCORE", plan.skipReason());
      steps.skip("IC", plan.skipReason());
    } else {
      if (hook != null) {
        steps.run("SCORE", () -> scoreboard[0] = hook.scoreDue(execution));
      } else {
        steps.skip("SCORE", "채점 훅 없음");
      }
      // IC 청크는 steps::run 으로 돌아 청크마다 단계 기록·flush·취소 감지를 받는다. 바깥 IC 단계는 청크 밖 조회(마지막 IC 일·캘린더) 실패를 격리한다
      steps.run("IC", () -> icService.computeIncremental(steps::run).forEach(r -> r.record(execution)));
    }

    // ② 시장 특징·스크리닝 (필수). 자기 호라이즌 종류는 그 창(적용 구간·rs120 노출)과 그 호라이즌 가중치 세트로
    final MarketFeatures[] marketBox = new MarketFeatures[1];
    final ScreeningResult[] screened = new ScreeningResult[1];
    steps.runOrThrow("FEATURES", () -> marketBox[0] = ownHorizon ? marketFeatures.features(baseDate, plan.horizonDays()) : marketFeatures.features(baseDate));
    steps.runOrThrow("SCREEN", () -> screened[0] = ownHorizon
        ? screening.screen(baseDate, kind, properties.getPickUniverse()).orElseThrow(() -> new IllegalStateException(kind + " 가중치 세트가 사라졌습니다"))
        : screening.screen(baseDate));
    // 정책 표는 같은 표를 이 종류의 픽 범위로 다시 계산한다(H20) — 프롬프트 regime 블록·가드·regime_json 이 같은 한도를 보게 특징 사본에 싣는다
    MarketFeatures market = withPlanPolicy(marketBox[0], plan);
    ScreeningResult result = screened[0];
    if (result.candidates().size() < plan.bounds().min()) {
      throw new IllegalStateException("후보가 너무 적습니다: " + result.candidates().size() + " (universe " + result.universeSize() + ")");
    }
    execution.putMetadata("markets", properties.getMarkets());
    execution.putMetadata("pickUniverse", properties.getPickUniverse().getCode());
    execution.putMetadata("universe", result.universeSize());
    execution.putMetadata("cut", result.cutSize());
    execution.putMetadata("candidates", result.candidates().size());
    execution.putMetadata("weightSetId", result.weightSetId());
    WeightSet weightSet = weightSets.find(result.weightSetId()).orElseThrow();

    // ③ 정량 top-N 섀도 (LLM 없음) — 격리
    if (plan.quantShadow()) {
      steps.run("SHADOW_QUANT", () -> saveQuantShadow(AdviceVariant.QUANT_TOPN, "shadowQuantAdviceId", execution, result, decision, market, plan));
    } else {
      steps.skip("SHADOW_QUANT", plan.skipReason());
    }
    // 유니버스 제한의 대조군(advice-v7): 같은 가중치·규칙으로 픽 필터만 뺀 top-N. 설정이 ALL 이면 QUANT_TOPN 과 같아 돌리지 않는다
    if (!plan.learningLoop()) {
      steps.skip("SHADOW_QUANT_BROAD", plan.skipReason());
    } else if (properties.getPickUniverse() == PickUniverse.ALL) {
      steps.skip("SHADOW_QUANT_BROAD", "pick-universe=ALL (QUANT_TOPN 과 동일)");
    } else {
      steps.run("SHADOW_QUANT_BROAD", () -> saveQuantShadow(AdviceVariant.QUANT_TOPN_BROAD, "shadowQuantBroadAdviceId", execution,
          screening.screen(baseDate, PickUniverse.ALL), decision, market, plan));
    }

    // ④ 프롬프트: 실적 블록·교훈은 누적 픽 게이트를 넘긴 뒤에만(메모리를 싣는 종류만)
    boolean memoryOn = plan.memory() && adviceWriter.countLivePicks(AdviceKind.DAILY) >= properties.getLesson().getMinPicks();
    final List<LessonRow> activeLessons = memoryOn ? activeLessons() : List.of();
    // 교훈의 regime 조건은 오늘 국면을 모르는 시점이라 직전 LIVE 판단의 국면으로 평가한다. trend 조건은 규칙이 기준일에 확정한 오늘 값으로 즉시 판정한다
    MarketRegimeCode previousRegime = activeLessons.isEmpty() ? null
        : adviceWriter.findLatest(AdviceKind.DAILY, AdviceVariant.LIVE, baseDate.minusDays(1)).map(AdviceHeader::regimeCode).orElse(null);
    Map<String, MarketTrendCode> trendCodes = market.trendCodes();
    List<CandidateRow> candidates = tagLessons(result.candidates(), activeLessons, previousRegime, trendCodes);
    ScreeningResult tagged = new ScreeningResult(result.baseDate(), result.universeSize(), result.cutSize(), result.weightSetId(), candidates);
    Map<String, Object> promptScoreboard = memoryOn ? scoreboard[0].promptBlock() : null;
    // 뉴스(advice-v4): 켜져 있으면 판단 시각 이전 창의 헤드라인. 조회 실패는 격리 — 뉴스 없이 판단한다
    final NewsBlock[] newsBox = new NewsBlock[1];
    if (!plan.news()) {
      steps.skip("NEWS", plan.skipReason());
    } else if (properties.getNews().isEnabled()) {
      List<String> candidateTickers = candidates.stream().map(CandidateRow::ticker).toList();
      steps.run("NEWS", () -> newsBox[0] = newsFeatures.getIfAvailable() == null ? null
          : newsFeatures.getIfAvailable().news(baseDate, candidateTickers).orElse(null));
    } else {
      steps.skip("NEWS", "뉴스 입력 비활성");
    }
    final NewsBlock news = newsBox[0];
    // 빠른 층(note-v1): 12:00 노트의 T+5 확정 빈도표 — 격리. 조회 실패·확정 노트 부족은 블록 없이 판단한다(단계는 OK, 부족 사유는 skip.NOTES 메타)
    final Map<String, Object>[] outcomesBox = newMapBox();
    if (plan.memory()) {
      boolean notesOk = steps.run("NOTES", () -> outcomesBox[0] = recentOutcomes.block(baseDate).orElse(null));
      if (notesOk && outcomesBox[0] == null) {
        execution.putMetadata("skip.NOTES", "확정 노트 < " + properties.getNote().getMinFinalized());
      }
    } else {
      steps.skip("NOTES", plan.skipReason());
    }
    final Map<String, Object> outcomes = outcomesBox[0];
    PromptPayload payload = promptBuilder.build(market, tagged, promptScoreboard, activeLessons, weightSet.enabledWeights(), decision.quality(), news,
        outcomes, plan.horizonDays());
    if (payload.truncated()) {
      execution.warn("입력 길이 상한으로 후보를 " + payload.candidatesIncluded() + "개로 줄였습니다");
    }
    // 메모리 요약: 세 층 중 하나라도 실렸으면 헤더 memory_json 에 남긴다(없으면 null) — NOMEM 창 시작점·사후 요인 분리
    Map<String, Object> memoryJson = memoryJson(outcomes, activeLessons, promptScoreboard);
    boolean memoryInjected = memoryJson != null;
    execution.putMetadata("promptChars", payload.json().length());
    execution.putMetadata("memoryOn", memoryOn);
    if (memoryInjected) {
      execution.putMetadata("memory", memoryJson);
    }
    execution.putMetadata("newsIds", payload.newsIds().size());
    // 섹터 맥락(advice-v6): 이름 + consistent·overheated — 가드의 주도 섹터 검증·확신 클램프에 쓴다. LIVE·섀도가 같은 맥락을 공유한다
    AdviceGuard.SectorContext sectorContext = AdviceGuard.SectorContext.of(market, candidates);
    String schema = AdviceSchemaFactory.schemaJson(payload.candidateTickers(), payload.sectorCodes(), payload.newsIds());
    String promptVersion = promptVersion(kind);

    // ⑤ 판단 (필수) → ⑥ 가드
    final MarketJudgeClient.JudgeResult[] judged = new MarketJudgeClient.JudgeResult[1];
    steps.runOrThrow("JUDGE", () -> judged[0] = judge.judge(systemPrompt(kind), payload, schema));
    MarketJudgeClient.JudgeResult jr = judged[0];
    execution.recordLlmUsage(jr.model(), promptVersion, jr.usage(), jr.reasoningTokens(), jr.cachedTokens());
    List<CandidateRow> included = candidates.subList(0, payload.candidatesIncluded());
    // M6: 합성 국면의 사전 등록 정책 표 한도를 가드가 기계 강제한다(LIVE·ADHOC·H20·LLM 섀도 모두 같은 표 — 섀도가 LIVE 와 한 요인만 다르게)
    AdviceGuard.Result guarded = guard.validate(jr.response(), included, sectorContext, trendCodes, news, market.policy(), plan.bounds());
    execution.putMetadata("guard", guarded.stats());
    if (market.regime() != null) {
      execution.putMetadata("regime", market.regime().labelText());
    }
    // 매수 전용(advice-v9): 가드 뒤 0픽은 관망 — 실패가 아니라 픽 0행 헤더로 저장·발행한다(KPI 는 관망일을 부가가치에서 빼고 따로 센다)
    if (guarded.abstained()) {
      execution.putMetadata("abstain", true);
    }
    if (guarded.removalRatio() > AdviceGuard.REMOVAL_ALERT_RATIO) {
      execution.markPartial(String.format("가드 제거율 %.0f%% (%d/%d) — 모델·프롬프트 점검 필요", guarded.removalRatio() * 100, guarded.removed(),
          guarded.originalPicks()));
    }

    // ⑦ LIVE 저장 (필수)
    AdviceHeader header = AdviceHeader.builder()
        .runId(execution.runId()).baseDate(baseDate).adviceKind(kind).variant(AdviceVariant.LIVE)
        .horizonDays(plan.horizonDays())
        .regimeCode(guarded.regime()).kospiDir(guarded.kospiDir()).kosdaqDir(guarded.kosdaqDir()).pUp(guarded.pUp())
        .regimeRationale(guarded.rationale()).leadingSectors(guarded.sectors()).summary(guarded.summary())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).trends(market.trends()).outlooks(guarded.outlooks())
        .dataAsOf(market.dataAsOf()).entryDate(market.entryDate()).exitDate(market.exitDate()).newsIds(payload.newsIds())
        .promptVersion(promptVersion).model(jr.model()).systemFingerprint(jr.responseId())
        .weightSetId(result.weightSetId()).activeLessonIds(activeLessons.stream().map(LessonRow::lessonId).toList())
        .dataQuality(decision.quality()).guard(guarded.stats()).memoryJson(memoryJson).regime(market.regime())
        .build();
    final long[] adviceId = new long[1];
    steps.runOrThrow("SAVE", () -> {
      adviceId[0] = adviceWriter.insertHeader(header);
      adviceWriter.insertCandidates(adviceId[0], included);
      adviceWriter.insertPicks(adviceId[0], guarded.picks());
      promptInputs.upsert(new PromptInputRow(execution.runId(), AdviceVariant.LIVE, promptVersion, promptSha256(kind),
          payload.json(), AdvisorJson.write(jr.options()), jr.rawText()));
      // 교훈 적용 카운트는 효과 판정(적용−비적용)의 분모라 DAILY 만 올린다
      for (LessonRow lesson : plan.learningLoop() ? activeLessons : List.<LessonRow>of()) {
        int applied = (int) included.stream().filter(c -> c.appliedLessonIds() != null && c.appliedLessonIds().contains(lesson.lessonId())).count();
        lessons.addApplied(lesson.lessonId(), applied);
      }
    });
    execution.putMetadata("adviceId", adviceId[0]);
    execution.putMetadata("picks", guarded.picks().size());

    // ⑧ 발행 (격리). 제목·안내 문구는 종류로 갈린다(DailyAdviceMessage) — H20 은 "20일 관점 추천" 별도 메시지
    Map<String, CandidateRow> byTicker = included.stream().collect(Collectors.toMap(CandidateRow::ticker, c -> c, (a, b) -> a, LinkedHashMap::new));
    steps.run("PUBLISH", () -> {
      DailyAdviceMessage message = DailyAdviceMessage.builder()
          .header(header.toBuilder().adviceId(adviceId[0]).build()).picks(guarded.picks()).candidates(byTicker)
          .marketLabel(String.join("·", properties.getMarkets()))
          .universeLabel(properties.getPickUniverse().getCode())
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

    // ⑨ 섀도 (격리). 요인 분리: NOMEM = 뉴스 그대로·메모리(recentOutcomes·lessons·scoreboard) 없음, NONEWS = 메모리 그대로·뉴스 없음 — LIVE 와 정확히 한 요인만 다르다.
    // LLM 섀도는 한 번에 1개 원칙이라 DAILY 에만 둔다(H20 은 규칙형 QUANT_TOPN 만)
    if (!plan.learningLoop()) {
      steps.skip("SHADOW_NOMEM", plan.skipReason());
      steps.skip("SHADOW_NONEWS", plan.skipReason());
      return;
    }
    if (memoryInjected && nomemShadowOpen(baseDate)) {
      steps.run("SHADOW_NOMEM", () -> saveLlmShadow(AdviceVariant.LLM_NOMEM, execution, market, tagged, weightSet, sectorContext, decision,
          null, List.of(), news, "shadowNomemAdviceId", null));
    } else {
      steps.skip("SHADOW_NOMEM", memoryInjected ? "섀도 기간 종료" : "메모리 미주입");
    }
    if (news != null && nonewsShadowOpen(baseDate)) {
      steps.run("SHADOW_NONEWS", () -> saveLlmShadow(AdviceVariant.LLM_NONEWS, execution, market, tagged, weightSet, sectorContext, decision,
          promptScoreboard, activeLessons, null, "shadowNonewsAdviceId", outcomes));
    } else {
      steps.skip("SHADOW_NONEWS", news == null ? "뉴스 없음" : "뉴스 섀도 기간 종료");
    }
  }

  /**
   * 종류의 픽 상한이 DAILY 와 다르면 정책 표를 그 상한으로 다시 계산한 국면을 실은 특징 사본(M7 H20). 같으면(DAILY·ADHOC) 그대로 — MarketRegimeService 가 이미 DAILY 상한으로 계산했다.
   * regime-policy-v2 부터 표는 pick-max 만 본다(하한 없음).
   */
  MarketFeatures withPlanPolicy(MarketFeatures market, KindPlan plan) {
    MarketRegime regime = market.regime();
    if (regime == null || plan.bounds().max() == properties.getPickMax()) {
      return market;
    }
    MarketRegime.Policy policy = regimePolicy.limits(regime.trend(), regime.vol(), plan.bounds().max());
    return market.withRegime(regime.toBuilder().policy(policy).build());
  }

  /** 종류의 시스템 프롬프트 (H20 = advice-h20-v2, 그 밖 = advice-v9) */
  private String systemPrompt(AdviceKind kind) {
    return kind == AdviceKind.H20 ? prompts.h20System() : prompts.adviceSystem();
  }

  /** 종류의 프롬프트 버전 */
  static String promptVersion(AdviceKind kind) {
    return kind == AdviceKind.H20 ? PromptResources.H20_VERSION : PromptResources.ADVICE_VERSION;
  }

  private String promptSha256(AdviceKind kind) {
    return kind == AdviceKind.H20 ? prompts.h20Sha256() : prompts.adviceSha256();
  }

  /**
   * DAILY 가 아닌 판단(ADHOC·H20)을 막을 사유. DAILY 게이트의 "이미 판단함"(DAILY LIVE 존재)과 마감은 이 종류들과 무관하므로 영업일·입력 준비만 보고,
   * 같은 기준일 같은 종류 LIVE 가 이미 있으면 막는다 — 유니크 (base_date, advice_kind, variant) 때문이기도 하지만, 기준일이 같으면 입력(일봉 지표)이 같아
   * 다시 돌려도 LLM 표본 잡음만 달라진다. 휴장일(금요일 공휴일의 H20 등)은 여기서 SKIP 된다.
   */
  Optional<String> offCycleBlocked(LocalDate baseDate, AdviceKind kind, AdvisorGateService.Decision decision) {
    if (!decision.tradingDay() || !decision.dataReady()) {
      return Optional.of(decision.reason());
    }
    return adviceWriter.find(baseDate, kind, AdviceVariant.LIVE)
        .map(existing -> "이미 " + kind.getDesc() + " 판단이 있습니다: " + baseDate + " (advice=" + existing.adviceId() + ")");
  }

  /**
   * 뉴스 없는 섀도를 돌릴 기간인지: 뉴스가 실린 첫 LIVE 판단부터 advisor.shadow.nonews-weeks 주 안. 첫 판단이 아직 없으면(오늘이 처음) 연다.
   */
  boolean nonewsShadowOpen(LocalDate baseDate) {
    return adviceWriter.firstNewsAdviceDate(AdviceKind.DAILY).map(first -> !baseDate.isAfter(first.plusWeeks(properties.getShadow().getNonewsWeeks()))).orElse(true);
  }

  /**
   * 메모리 없는 섀도(LLM_NOMEM)를 돌릴 기간인지: 메모리가 처음 실린 LIVE 판단(memory_json IS NOT NULL)부터 advisor.shadow.nomem-weeks 주 안 — NONEWS 와 동형.
   * 첫 판단이 아직 없으면(오늘이 처음) 연다. 2026-09-21 까지 nomem-weeks 를 읽는 코드가 없어 NOMEM 이 영구 병행이던 결함의 수정(운영 문서 §8 사전 등록 판정).
   */
  boolean nomemShadowOpen(LocalDate baseDate) {
    return adviceWriter.firstMemoryAdviceDate(AdviceKind.DAILY).map(first -> !baseDate.isAfter(first.plusWeeks(properties.getShadow().getNomemWeeks()))).orElse(true);
  }

  /**
   * 헤더 memory_json: 프롬프트에 실린 메모리 요약 {recentOutcomes: 행수, lessons: [id], scoreboard: bool}. 셋 다 없으면 null(주입 없음) — NOMEM 섀도도 null.
   */
  static Map<String, Object> memoryJson(Map<String, Object> recentOutcomes, List<LessonRow> lessons, Map<String, Object> scoreboard) {
    boolean hasOutcomes = recentOutcomes != null && !recentOutcomes.isEmpty();
    boolean hasLessons = lessons != null && !lessons.isEmpty();
    boolean hasScoreboard = scoreboard != null && !scoreboard.isEmpty();
    if (!hasOutcomes && !hasLessons && !hasScoreboard) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("recentOutcomes", RecentOutcomesService.rowCount(hasOutcomes ? recentOutcomes : null));
    m.put("lessons", hasLessons ? lessons.stream().map(LessonRow::lessonId).toList() : List.of());
    m.put("scoreboard", hasScoreboard);
    return m;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object>[] newMapBox() {
    return (Map<String, Object>[]) new Map[1];
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
   * 정량 top-N 동일가중 섀도: LLM 없이 점수 상위 N 을 LONG·확신 0.55 로 저장. QUANT_TOPN 은 LLM 부가가치의 대조군, QUANT_TOPN_BROAD 는 픽 유니버스 제한의 대조군
   * (result 가 ALL 유니버스 스크리닝) — 둘은 후보 모집단만 다르고 규칙·N 이 같다. 종류·호라이즌·적용 구간은 LIVE 와 같다(H20 섀도는 kind=H20·h=20, M7).
   */
  private void saveQuantShadow(AdviceVariant variant, String metadataKey, AdvisorExecution execution, ScreeningResult result,
      AdvisorGateService.Decision decision, MarketFeatures market, KindPlan plan) {
    if (adviceWriter.find(result.baseDate(), plan.kind(), variant).isPresent()) {
      return;
    }
    int n = Math.min(properties.getShadow().getQuantTopN(), result.candidates().size());
    Map<String, MarketTrendCode> trendCodes = market.trendCodes();
    AdviceHeader header = AdviceHeader.builder().runId(execution.runId()).baseDate(result.baseDate()).adviceKind(plan.kind())
        .variant(variant).horizonDays(plan.horizonDays()).weightSetId(result.weightSetId())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).entryDate(market.entryDate()).exitDate(market.exitDate())
        .dataQuality(decision.quality()).promptVersion("quant").model("quant-top-" + n).build();
    long id = adviceWriter.insertHeader(header);
    adviceWriter.insertCandidates(id, result.candidates());
    List<PickRow> picks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      picks.add(PickRow.builder().ticker(result.candidates().get(i).ticker()).pickRank(i + 1).direction(PickDirection.LONG).conviction(0.55).build());
    }
    adviceWriter.insertPicks(id, picks);
    execution.putMetadata(metadataKey, id);
  }

  /**
   * LLM 섀도 1회: 같은 시장·후보 입력에서 요인 하나만 바꿔 한 번 더 판단해 variant 로 저장한다 (발행 없음).
   * LLM_NOMEM 은 메모리(실적 블록·교훈·recentOutcomes) 없이·뉴스는 그대로, LLM_NONEWS 는 뉴스 없이·메모리는 그대로 — 각 섀도가 LIVE 와 정확히 한 요인만 달라야
   * (LIVE − 섀도) 가 그 요인의 가치가 된다. 헤더 memory_json 은 이 섀도에 실제로 실린 메모리로 적는다(NOMEM 은 null, NONEWS 는 LIVE 와 같은 값).
   */
  private void saveLlmShadow(AdviceVariant variant, AdvisorExecution execution, MarketFeatures market, ScreeningResult screened, WeightSet weightSet,
      AdviceGuard.SectorContext sectorContext, AdvisorGateService.Decision decision, Map<String, Object> scoreboard, List<LessonRow> lessons,
      NewsBlock news, String metadataKey, Map<String, Object> recentOutcomes) {
    if (adviceWriter.find(screened.baseDate(), AdviceKind.DAILY, variant).isPresent()) {
      return;
    }
    PromptPayload payload = promptBuilder.build(market, screened, scoreboard, lessons, weightSet.enabledWeights(), decision.quality(), news, recentOutcomes);
    String schema = AdviceSchemaFactory.schemaJson(payload.candidateTickers(), payload.sectorCodes(), payload.newsIds());
    MarketJudgeClient.JudgeResult jr = judge.judge(prompts.adviceSystem(), payload, schema);
    execution.recordLlmUsage(jr.model(), PromptResources.ADVICE_VERSION, jr.usage(), jr.reasoningTokens(), jr.cachedTokens());
    List<CandidateRow> included = screened.candidates().subList(0, payload.candidatesIncluded());
    Map<String, MarketTrendCode> trendCodes = market.trendCodes();
    AdviceGuard.Result guarded = guard.validate(jr.response(), included, sectorContext, trendCodes, news, market.policy());
    AdviceHeader header = AdviceHeader.builder().runId(execution.runId()).baseDate(screened.baseDate()).adviceKind(AdviceKind.DAILY)
        .variant(variant).horizonDays(properties.getHorizonDays())
        .regimeCode(guarded.regime()).kospiDir(guarded.kospiDir()).kosdaqDir(guarded.kosdaqDir()).pUp(guarded.pUp())
        .regimeRationale(guarded.rationale()).leadingSectors(guarded.sectors()).summary(guarded.summary())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).trends(market.trends()).outlooks(guarded.outlooks())
        .dataAsOf(market.dataAsOf()).entryDate(market.entryDate()).exitDate(market.exitDate()).newsIds(payload.newsIds())
        .promptVersion(PromptResources.ADVICE_VERSION).model(jr.model()).systemFingerprint(jr.responseId())
        .weightSetId(screened.weightSetId()).activeLessonIds(lessons.stream().map(LessonRow::lessonId).toList())
        .dataQuality(decision.quality()).guard(guarded.stats()).memoryJson(memoryJson(recentOutcomes, lessons, scoreboard)).regime(market.regime()).build();
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

  /** 테스트용 */
  Optional<ScoreHook> hook() {
    return Optional.ofNullable(scoreHook.getIfAvailable());
  }
}
