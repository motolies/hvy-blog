package kr.hvy.blog.modules.advisor.application.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.slack.LongTermAdviceMessage;
import kr.hvy.blog.modules.advisor.client.llm.LongTermNarrativeResponse;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalValue;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 장기(H60·H180) 규칙 추천 파이프라인(M8). {@link H60AdviseJob}(격주 금요일)·{@link H180AdviseJob}(매월 첫 거래일)이 종류만 바꿔 부른다 — 수시 판단이
 * AdviseJob 을 공유하는 것과 같은 구성이다.
 * <pre>
 * 게이트(영업일·입력 준비·같은 기준일 같은 종류 없음) → 시장 특징(h 창: 적용 구간·섹터 rs120·국면 맥락) → 규칙 순위(LongTermScreeningService: 사전 고정 가중치
 * 장기 팩터 백분위 가중합, KOSPI200 PIT, 섹터 상한) → 서술(LLM, ticker enum = 규칙 픽 N, 격리·fail-open) → 가드(규칙 순서 정본, 서술만 매칭) →
 * LIVE 저장(kind·horizon_days·regime_json) → Slack("판정 불가: 표본 부족, 2년 이상 필요")
 * </pre>
 * 선택에 LLM 이 없으므로 섀도(QUANT_TOPN)를 두지 않는다 — LIVE 자체가 규칙이다. 국면 정책 표도 적용하지 않는다(규칙 선택을 사후에 깎으면 규칙 성과를 잴 수 없다):
 * regime_json 은 맥락 스냅샷으로만 저장하고 policy 는 null 이다. 채점은 ScoreJob 이 M5 인프라로 자기 호라이즌(60·180) 하나로만 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class LongTermAdviseJob {

  static final String RULE_ONLY_MODEL = "rule-only";

  private final AdvisorProperties properties;
  private final AdvisorGateService gate;
  private final MarketFeatureService marketFeatures;
  private final LongTermScreeningService screening;
  private final PromptResources prompts;
  private final MarketJudgeClient judge;
  private final AdviceWriter adviceWriter;
  private final PromptInputWriter promptInputs;
  private final AdvisorNotifier notifier;

  /**
   * 유일한 생성자(AdviseJob 과 같은 이유). 서술 모델은 판단 모델(judge) — 장기 thesis 는 일일 판단과 같은 품질 기준으로 읽힌다.
   */
  public LongTermAdviseJob(AdvisorProperties properties, AdvisorGateService gate, MarketFeatureService marketFeatures, LongTermScreeningService screening,
      PromptResources prompts, @Qualifier(MarketJudgeClient.JUDGE_BEAN) MarketJudgeClient judge, AdviceWriter adviceWriter, PromptInputWriter promptInputs,
      AdvisorNotifier notifier) {
    this.properties = properties;
    this.gate = gate;
    this.marketFeatures = marketFeatures;
    this.screening = screening;
    this.prompts = prompts;
    this.judge = judge;
    this.adviceWriter = adviceWriter;
    this.promptInputs = promptInputs;
    this.notifier = notifier;
  }

  /**
   * 장기 규칙 추천 1건. kind 는 H60 또는 H180.
   */
  void advise(AdvisorExecution execution, AdviceKind kind) {
    if (kind != AdviceKind.H60 && kind != AdviceKind.H180) {
      throw new IllegalArgumentException("장기 규칙 추천은 H60·H180 만: " + kind);
    }
    int h = properties.decisionHorizon(kind);
    LocalDate baseDate = execution.baseDate();
    execution.putMetadata("adviceKind", kind.getCode());
    execution.putMetadata("horizonDays", h);
    AdvisorGateService.Decision decision = gate.decide(baseDate, LocalTime.now(MarketClock.KST));
    if (!decision.tradingDay() || !decision.dataReady()) {
      execution.skip(decision.reason());
      return;
    }
    Optional<AdviceHeader> existing = adviceWriter.find(baseDate, kind, AdviceVariant.LIVE);
    if (existing.isPresent()) {
      execution.skip("이미 " + kind.getDesc() + " 추천이 있습니다: " + baseDate + " (advice=" + existing.get().adviceId() + ")");
      return;
    }
    execution.putMetadata("dataQuality", decision.quality().getCode());
    AdvisorProperties.LongTerm lt = properties.getLongTerm();
    AdvisorSteps steps = new AdvisorSteps(execution);

    // ① 시장 특징(h 창)·규칙 순위 (필수)
    AtomicReference<MarketFeatures> marketBox = new AtomicReference<>();
    AtomicReference<LongTermScorer.Ranking> rankingBox = new AtomicReference<>();
    steps.runOrThrow("FEATURES", () -> marketBox.set(marketFeatures.features(baseDate, h)));
    steps.runOrThrow("SCREEN", () -> rankingBox.set(screening.rank(baseDate)));
    MarketFeatures market = marketBox.get();
    LongTermScorer.Ranking ranking = rankingBox.get();
    if (ranking.pickTickers().size() < lt.getPickMin()) {
      throw new IllegalStateException("장기 규칙 후보가 너무 적습니다: " + ranking.pickTickers().size() + " (universe " + ranking.universeSize()
          + ", eligible " + ranking.eligibleSize() + ")");
    }
    execution.putMetadata("pickUniverse", properties.getPickUniverse().getCode());
    execution.putMetadata("universe", ranking.universeSize());
    execution.putMetadata("eligible", ranking.eligibleSize());
    execution.putMetadata("candidates", ranking.candidates().size());
    execution.putMetadata("weights", lt.getWeights());
    // 국면은 맥락으로만 — 정책 표 한도는 규칙 선택에 적용하지 않으므로 스냅샷에서 뺀다(적용되지 않은 한도를 저장하면 사후 분리가 헷갈린다)
    MarketRegime context = market.regime() == null ? null : market.regime().toBuilder().policy(null).build();

    // ② 서술 (격리·fail-open): 실패하면 서술 없이 규칙 픽으로 발행한다
    List<CandidateRow> pickRows = ranking.candidates().subList(0, ranking.pickTickers().size());
    String payload = AdvisorJson.write(payload(baseDate, h, market, context, pickRows, lt.getWeights()));
    String schema = LongTermNarrativeSchemaFactory.schemaJson(ranking.pickTickers());
    execution.putMetadata("promptChars", payload.length());
    AtomicReference<MarketJudgeClient.CallResult<LongTermNarrativeResponse>> called = new AtomicReference<>();
    AtomicReference<String> failure = new AtomicReference<>();
    steps.run("NARRATE", () -> {
      try {
        called.set(judge.call(prompts.longTermSystem(), payload, schema, LongTermNarrativeResponse.class));
      } catch (RuntimeException e) {
        failure.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        throw e;
      }
    });
    MarketJudgeClient.CallResult<LongTermNarrativeResponse> jr = called.get();
    if (jr != null) {
      execution.recordLlmUsage(jr.model(), PromptResources.LONGTERM_VERSION, jr.usage(), jr.reasoningTokens(), jr.cachedTokens());
    } else {
      execution.warn("장기 서술 실패 — 서술 없이 규칙 픽으로 발행합니다: " + failure.get());
    }
    LongTermNarrativeGuard.Result guarded = LongTermNarrativeGuard.apply(ranking.pickTickers(), jr == null ? null : jr.value(), failure.get());
    Map<String, Object> guardJson = new LinkedHashMap<>(guarded.stats());
    guardJson.put("weights", lt.getWeights());
    guardJson.put("minCoverage", lt.getMinCoverage());
    guardJson.put("financialPeriodType", lt.getFinancialPeriodType());
    guardJson.put("verdict", AdvisorKpiService.UNJUDGEABLE_LABEL);
    execution.putMetadata("guard", guardJson);
    execution.putMetadata("narrative", guarded.stats().get("narrative"));

    // ③ 저장 (필수): 후보 = 규칙 상위 candidate-limit(픽−후보군 평가의 모집단), 픽 = 규칙 상위 N(순서 불변)
    Map<String, MarketTrendCode> trendCodes = market.trendCodes();
    AdviceHeader header = AdviceHeader.builder()
        .runId(execution.runId()).baseDate(baseDate).adviceKind(kind).variant(AdviceVariant.LIVE).horizonDays(h)
        .summary(guarded.summary())
        .trendKospi(trendCodes.get("0001")).trendKosdaq(trendCodes.get("1001")).trends(market.trends())
        .dataAsOf(market.dataAsOf()).entryDate(market.entryDate()).exitDate(market.exitDate()).newsIds(List.of())
        .promptVersion(PromptResources.LONGTERM_VERSION).model(jr == null ? RULE_ONLY_MODEL : jr.model()).systemFingerprint(jr == null ? null : jr.responseId())
        .activeLessonIds(List.of()).dataQuality(decision.quality()).guard(guardJson).regime(context)
        .build();
    final long[] adviceId = new long[1];
    steps.runOrThrow("SAVE", () -> {
      adviceId[0] = adviceWriter.insertHeader(header);
      adviceWriter.insertCandidates(adviceId[0], ranking.candidates());
      adviceWriter.insertPicks(adviceId[0], guarded.picks());
      if (jr != null) {
        promptInputs.upsert(new PromptInputRow(execution.runId(), AdviceVariant.LIVE, PromptResources.LONGTERM_VERSION, prompts.longTermSha256(), payload,
            AdvisorJson.write(jr.options()), jr.rawText()));
      }
    });
    execution.putMetadata("adviceId", adviceId[0]);
    execution.putMetadata("picks", guarded.picks().size());

    // ④ 발행 (격리)
    Map<String, CandidateRow> byTicker = ranking.candidates().stream()
        .collect(Collectors.toMap(CandidateRow::ticker, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    steps.run("PUBLISH", () -> {
      LongTermAdviceMessage message = LongTermAdviceMessage.builder()
          .header(header.toBuilder().adviceId(adviceId[0]).build()).picks(guarded.picks()).candidates(byTicker).weights(lt.getWeights())
          .verdictLabel(AdvisorKpiService.UNJUDGEABLE_LABEL).narrativeFailed(jr == null).universeLabel(properties.getPickUniverse().getCode())
          .runId(execution.runId()).promptTokens(execution.promptTokens()).completionTokens(execution.completionTokens())
          .build();
      if (notifier.publish(message)) {
        adviceWriter.markPublished(adviceId[0], Instant.now());
      } else {
        execution.warn("장기 추천 Slack 발행 실패 — 추천은 저장됨 (advice=" + adviceId[0] + ")");
      }
    });
  }

  /**
   * 서술 모델 입력: 기준일·호라이즌·판정 상태·적용 구간·팩터 정의와 가중치·국면 맥락·섹터(rs20/60/120)·테마·규칙 픽 표(팩터 원값 v_·백분위 p_).
   * 확신·선택권이 없는 서술 전용 입력이라 후보 전체가 아니라 픽만 싣는다.
   */
  static Map<String, Object> payload(LocalDate baseDate, int h, MarketFeatures market, MarketRegime context, List<CandidateRow> picks,
      Map<String, Double> weights) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("asOf", baseDate.toString());
    root.put("horizonDays", h);
    root.put("verdict", AdvisorKpiService.UNJUDGEABLE_LABEL);
    if (market.entryDate() != null && market.exitDate() != null) {
      Map<String, Object> window = new LinkedHashMap<>();
      window.put("entry", market.entryDate().toString());
      window.put("exit", market.exitDate().toString());
      window.put("entryRule", "다음 영업일 시가");
      window.put("exitRule", h + "번째 영업일 종가");
      root.put("window", window);
    }
    List<SignalCode> factors = SignalCode.longTerm().stream().filter(f -> weights.getOrDefault(f.getCode(), 0.0) > 0).toList();
    List<List<Object>> factorRows = new ArrayList<>();
    for (SignalCode f : factors) {
      factorRows.add(List.of(f.getCode(), f.getDesc(), weights.get(f.getCode()), f.isHigherIsBetter()));
    }
    root.put("factors", Map.of("columns", List.of("code", "desc", "weight", "higherIsBetter"), "rows", factorRows));
    if (context != null) {
      root.put("regime", AdvicePromptBuilder.regimeBlock(context));
    }
    Map<String, Object> sectors = new LinkedHashMap<>();
    sectors.put("columns", List.of("code", "name", "rs20", "rs60", "rs120", "consistent"));
    sectors.put("top", market.topSectors() == null ? List.of() : market.topSectors().stream().map(LongTermAdviseJob::sectorRow).toList());
    sectors.put("bottom", market.bottomSectors() == null ? List.of() : market.bottomSectors().stream().map(LongTermAdviseJob::sectorRow).toList());
    root.put("sectors", sectors);
    if (context != null && context.themes() != null && !context.themes().isEmpty()) {
      List<List<Object>> themeRows = new ArrayList<>();
      for (MarketRegime.Theme t : context.themes()) {
        List<Object> row = new ArrayList<>();
        row.add(t.code());
        row.add(t.members());
        row.add(AdvicePromptBuilder.round(t.rs5()));
        row.add(AdvicePromptBuilder.round(t.rs20()));
        row.add(AdvicePromptBuilder.round(t.rs60()));
        row.add(AdvicePromptBuilder.round(t.breadth(), 3));
        row.add(t.strength() == null ? null : t.strength().getCode());
        row.add(t.leaders() == null ? List.of() : t.leaders());
        themeRows.add(row);
      }
      root.put("theme", Map.of("columns", AdvicePromptBuilder.THEME_COLUMNS, "rows", themeRows));
    }
    List<String> columns = new ArrayList<>(List.of("rank", "tkr", "name", "sec", "theme", "score", "coverage", "fiscalPeriod"));
    for (SignalCode f : factors) {
      columns.add("v_" + f.getCode());
      columns.add("p_" + f.getCode());
    }
    List<List<Object>> rows = new ArrayList<>();
    for (CandidateRow c : picks) {
      List<Object> row = new ArrayList<>();
      row.add(c.quantRank());
      row.add(c.ticker());
      row.add(c.stockName());
      row.add(c.sectorCode());
      row.add(c.features() == null ? null : c.features().get("theme"));
      row.add(AdvicePromptBuilder.round(c.quantScore()));
      row.add(c.features() == null ? null : c.features().get("coverage"));
      row.add(c.features() == null ? null : c.features().get("fiscalPeriod"));
      for (SignalCode f : factors) {
        SignalValue v = c.signals() == null ? null : c.signals().get(f.getCode());
        row.add(v == null ? null : AdvicePromptBuilder.round(v.raw()));
        row.add(v == null || v.raw() == null ? null : AdvicePromptBuilder.round(v.pct()));
      }
      rows.add(row);
    }
    Map<String, Object> pickBlock = new LinkedHashMap<>();
    pickBlock.put("columns", columns);
    pickBlock.put("rows", rows);
    root.put("picks", pickBlock);
    return root;
  }

  /** 섹터 1행: code, name, rs20, rs60, rs120(h ≥ H60 이라 채워짐), consistent */
  private static List<Object> sectorRow(MarketFeatures.SectorFeature s) {
    List<Object> row = new ArrayList<>();
    row.add(s.code());
    row.add(s.name());
    row.add(AdvicePromptBuilder.round(s.rs20()));
    row.add(AdvicePromptBuilder.round(s.rs60()));
    row.add(AdvicePromptBuilder.round(s.rs120()));
    row.add(s.consistent());
    return row;
  }
}
