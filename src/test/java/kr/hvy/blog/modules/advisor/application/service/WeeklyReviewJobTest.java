package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * 주간 검토 흐름: 채점·IC → 가중치 세트 활성화(변경 줄) → 교훈 게이트(누적 픽) → 재현성 3회(Jaccard) → 보고 발행 → 보존 정리. 단계 실패는 격리된다.
 */
class WeeklyReviewJobTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final ScoreJob scoreJob = mock(ScoreJob.class);
  private final SignalIcService icService = mock(SignalIcService.class);
  private final WeightSetRepository weightSets = mock(WeightSetRepository.class);
  private final LessonService lessonService = mock(LessonService.class);
  private final AdvisorKpiService kpi = mock(AdvisorKpiService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final PromptInputWriter promptInputs = mock(PromptInputWriter.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository notes = mock(kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository.class);
  private final TradingCalendar calendar = mock(TradingCalendar.class);
  private final AtomicInteger judgeCalls = new AtomicInteger();
  private final LocalDate today = LocalDate.of(2026, 9, 13);
  /** n번째(1부터) 재현성 재실행에 답한 공급자·모델 — 기본은 표시 없음(null → 모델은 힌트 "judge"). 폴백으로 재실행마다 판단 주체가 갈리는 상황을 흉내 낸다 */
  private IntFunction<LlmProvider> judgeProvider = n -> null;
  private IntFunction<String> judgeModel = n -> null;
  private WeeklyReviewJob job;

  @BeforeEach
  void setUp() {
    ChatModel judgeStub = prompt -> {
      int n = judgeCalls.incrementAndGet();
      String picks = n == 2 ? "\"T00\",\"T01\",\"T03\"" : "\"T00\",\"T01\",\"T02\"";
      String reply = "{\"regime\":{\"code\":\"RISK_ON\",\"kospiDir\":\"UP\",\"kosdaqDir\":\"NEUTRAL\",\"pUp\":\"0.70\",\"rationale\":\"r\"},"
          + "\"sectors\":[{\"code\":\"G2510\",\"reason\":\"s\"}],\"picks\":[" + picksJson(picks) + "],\"summary\":\"요약\"}";
      ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
      if (judgeModel.apply(n) != null) {
        metadata.model(judgeModel.apply(n));
      }
      if (judgeProvider.apply(n) != null) {
        metadata.keyValue(LlmResponses.PROVIDER_METADATA_KEY, judgeProvider.apply(n).name());
      }
      return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))), metadata.build());
    };
    ChatModel assistStub = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("{\"proposals\":[],\"nullResults\":[],\"retireCandidates\":[]}"))));
    job = new WeeklyReviewJob(properties, scoreJob, icService, weightSets, lessonService, kpi, adviceWriter, promptInputs, new PromptResources(),
        new MarketJudgeClient(ChatClient.create(judgeStub), "judge"), new MarketJudgeClient(ChatClient.create(assistStub), "assist"), notifier, jdbc,
        notes, calendar);
    when(scoreJob.scoreDue(any())).thenReturn(AdviseJob.Scoreboard.empty());
    when(calendar.previousTradingDays(any(), anyInt())).thenReturn(List.of(today.minusDays(14)));
    when(notes.countStaleOpen(any())).thenReturn(0);
    when(adviceWriter.findRange(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
    when(icService.computeIncremental(any())).thenReturn(List.of());
    when(icService.latestScorableDate(anyInt())).thenReturn(Optional.of(today.minusDays(7)));
    WeightSet active = WeightSet.builder().weightSetId(1L).source(WeightSetSource.SEED).active(true).weights(List.of(
        SignalWeightRow.builder().signalCode("MOM_20D").baseWeight(0.12).multiplier(1).weight(0.12).enabled(true).build(),
        SignalWeightRow.builder().signalCode("TV_SURGE").baseWeight(0.10).multiplier(1).weight(0.10).enabled(true).build())).build();
    when(weightSets.active(5)).thenReturn(Optional.of(active));
    when(weightSets.insert(any(), any(Boolean.class))).thenReturn(2L);
    when(kpi.variantSummaries(any(), any())).thenReturn(List.of(
        new AdvisorKpiService.VariantSummary(AdviceVariant.LIVE, 10, 60, 0.55, 0.006, 0.004, 0.003, 0.002, 0.004, null, 0)));
    when(kpi.regimeSummary(any(), any(), any())).thenReturn(new AdvisorKpiService.RegimeSummary(10, 0.6, 0.22, 0.12));
    when(kpi.trendSummary(any(), any(), any())).thenReturn(new AdvisorKpiService.TrendSummary(0, null, null, 0, null));
    when(kpi.morningSummary(any(), any(), any())).thenReturn(new AdvisorKpiService.MorningSummary(0, null, null));
    when(kpi.calibration(any(), any())).thenReturn(List.of(new AdvisorKpiService.CalibrationRow(0.7, 20, 0.55, 0.004)));
    when(adviceWriter.countLivePicks(AdviceKind.DAILY)).thenReturn(0);
    when(adviceWriter.findLatest(any(), any(), any())).thenReturn(Optional.of(AdviceHeader.builder().adviceId(842L).runId(77L).baseDate(today.minusDays(2)).build()));
    when(promptInputs.find(77L, AdviceVariant.LIVE)).thenReturn(Optional.of(new PromptInputRow(77L, AdviceVariant.LIVE, "advice-v1", "sha",
        frozenPayload(), "{}", null)));
    when(promptInputs.deleteOlderThan(anyInt())).thenReturn(3);
    when(notifier.publish(any())).thenReturn(true);
    when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Double.class), any(), any())).thenReturn(0.1);
    when(jdbc.queryForList(anyString(), any(Class.class))).thenReturn(List.of("G2510"));
  }

  @Test
  @DisplayName("가중치 세트를 갱신·활성화하고, 메모리 미활성이면 교훈을 건너뛰며, 재현성 3회를 재고 보고를 발행한다")
  void fullFlowWithoutMemory() {
    WeightSet proposed = WeightSet.builder().source(WeightSetSource.WEEKLY).nEff(24).weights(List.of(
        SignalWeightRow.builder().signalCode("MOM_20D").baseWeight(0.12).multiplier(1.5).weight(0.16).enabled(true).icMean(0.045).tStat(3.2).nDays(120).build(),
        SignalWeightRow.builder().signalCode("TV_SURGE").baseWeight(0.10).multiplier(0.5).weight(0.06).enabled(true).icMean(-0.01).tStat(-1.0).nDays(120).flagged(true).build())).build();
    when(icService.proposeWeightSet(org.mockito.ArgumentMatchers.eq(5), any(), any(), any())).thenReturn(Optional.of(proposed));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(execution.metadata("weightSetId")).isEqualTo(2L);
    verify(weightSets).insert(any(), org.mockito.ArgumentMatchers.eq(true));
    verify(lessonService, never()).apply(any(), any(), any(), any());
    assertThat(judgeCalls.get()).as("재현성 3회").isEqualTo(3);
    assertThat(execution.llmCalls()).isEqualTo(3);
    double jaccard = (Double) execution.metadata("reproJaccard");
    assertThat(jaccard).isCloseTo((0.5 + 1.0 + 0.5) / 3, org.assertj.core.data.Offset.offset(0.01)); // {0,1,2} vs {0,1,3} vs {0,1,2}
    assertThat(execution.warnings()).anyMatch(w -> w.contains("재현성 낮음")).anyMatch(w -> w.contains("IC 음수"));
    assertThat(execution.metadata("reproModels")).as("재실행에 쓰인 판단 주체(공급자 표시 없음 → 모델만)").isEqualTo(List.of("judge"));
    assertThat(execution.metadata("reproComparable")).as("판단 주체가 하나고 원 판단 모델을 모르면 기존대로 판정한다").isNull();
    verify(notifier).publish(any());
    assertThat(execution.metadata("promptInputsDeleted")).isEqualTo(3);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name)
        .containsExactly("SCORE", "IC", "WEIGHTS", "WEIGHTS@20", "LESSONS", "REPRO", "KPI", "MEMORY", "REPORT", "CLEANUP");
    assertThat(execution.steps().get(4).status()).isEqualTo("SKIPPED");
    // M5: h=20 은 활성 세트가 없고 제안도 게이트 미달(empty) — DAILY 세트는 한 번만 저장되고 보고에 [h=20] 줄이 남는다
    verify(icService).proposeWeightSet(org.mockito.ArgumentMatchers.eq(20), any(), any(), any());
    verify(weightSets, org.mockito.Mockito.times(1)).insert(any(), any(Boolean.class));
    // note-v1: NOMEM 쌍이 없으면 "쌍 없음", 확정 지연 노트 0건
    ArgumentCaptor<kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage> message =
        ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().toBlocks().toString()).contains("LIVE vs NOMEM(최근 4주): 쌍 없음").contains("OPEN 노트 10영업일 초과: 0건");
    verify(notes).countStaleOpen(today.minusDays(14));
  }

  // ----- 공급자 폴백(2026-11-11): 재현성은 재실행끼리 판단 주체가 같을 때만 판정 (Jaccard 는 재실행끼리만 짝지어 계산한다) -----

  /** 원 LIVE 판단의 헤더 모델과 입력 스냅샷 options_json 을 지정한다 */
  private void stubLiveJudge(String model, String optionsJson) {
    when(adviceWriter.findLatest(any(), any(), any()))
        .thenReturn(Optional.of(AdviceHeader.builder().adviceId(842L).runId(77L).baseDate(today.minusDays(2)).model(model).build()));
    when(promptInputs.find(77L, AdviceVariant.LIVE)).thenReturn(Optional.of(new PromptInputRow(77L, AdviceVariant.LIVE, "advice-v1", "sha",
        frozenPayload(), optionsJson, null)));
  }

  private String reportText() {
    ArgumentCaptor<kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage> message =
        ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage.class);
    verify(notifier).publish(message.capture());
    return message.getValue().toBlocks().toString();
  }

  @Test
  @DisplayName("재현성: 재실행끼리 공급자·모델이 갈리면(폴백) Jaccard 가 낮아도 '재현성 낮음' 경고를 내지 않고, 쓰인 판단 주체 목록을 메타와 보고 줄에 남긴다")
  void reproMixedProvidersSuppressWarning() {
    // 1회차 Claude, 2·3회차 OpenAI — 2회차 픽이 달라 Jaccard 0.67 (< 0.70)
    judgeProvider = n -> n == 1 ? LlmProvider.CLAUDE : LlmProvider.OPENAI;
    judgeModel = n -> n == 1 ? "claude-x" : "gpt-x";
    stubLiveJudge("claude-x", "{\"model\":\"claude-x\",\"provider\":\"CLAUDE\"}");

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(judgeCalls.get()).isEqualTo(3);
    assertThat(execution.llmCalls()).isEqualTo(3);
    assertThat((Double) execution.metadata("reproJaccard")).as("측정값은 그대로 기록").isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.01));
    assertThat(execution.warnings()).noneMatch(w -> w.contains("재현성 낮음"));
    assertThat(execution.metadata("reproModels")).isEqualTo(List.of("CLAUDE:claude-x", "OPENAI:gpt-x"));
    assertThat(execution.metadata("reproComparable")).isEqualTo(false);
    assertThat(reportText()).contains("재실행의 판단 주체가 섞임 — 재현성 판정 보류 (재실행 CLAUDE:claude-x, OPENAI:gpt-x · 원 판단 CLAUDE:claude-x)")
        .doesNotContain("재현성 낮음");
    assertThat(execution.steps()).filteredOn(step -> step.name().equals("REPRO")).extracting(AdvisorExecution.StepResult::status).containsExactly("OK");
  }

  @Test
  @DisplayName("재현성: 재실행이 모두 한 모델이면 원 LIVE 판단과 모델이 달라도 판정한다(Jaccard 는 재실행끼리의 값) — 경고를 내고 무엇의 재현성인지 보고 줄에 남긴다")
  void reproHomogeneousRerunsAreJudgedEvenWhenLiveJudgeDiffers() {
    // Claude 가 차단된 주간처럼 재실행 3회가 전부 OpenAI 로 돌았다. provider 키가 없는 이전 스냅샷이라 원 판단은 헤더 모델로만 가린다
    judgeProvider = n -> LlmProvider.OPENAI;
    judgeModel = n -> "gpt-x";
    stubLiveJudge("claude-x", "{}");

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.warnings()).as("재실행끼리의 Jaccard 0.67 은 gpt-x 의 재현성으로 유효하다 — 경고만 봐도 누구의 재현성인지 알 수 있다")
        .anyMatch(w -> w.contains("재현성 낮음") && w.contains("재실행 주체 OPENAI:gpt-x · 원 판단 claude-x"));
    assertThat(execution.metadata("reproModels")).isEqualTo(List.of("OPENAI:gpt-x"));
    assertThat(execution.metadata("reproComparable")).as("재실행끼리는 비교 가능").isNull();
    assertThat(execution.metadata("reproDiffersFromLive")).isEqualTo(true);
    assertThat(reportText()).contains("참고: 재실행 판단 주체(OPENAI:gpt-x)가 원 판단(claude-x)과 다름").doesNotContain("판정 보류");
  }

  @Test
  @DisplayName("재현성: 재실행과 원 LIVE 판단의 공급자·모델이 모두 같으면 기존대로 Jaccard < 0.70 에 '재현성 낮음' 경고를 낸다")
  void reproSameJudgeStillWarns() {
    judgeProvider = n -> LlmProvider.CLAUDE;
    judgeModel = n -> "claude-x";
    stubLiveJudge("claude-x", "{\"model\":\"claude-x\",\"provider\":\"CLAUDE\"}");

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.warnings()).anyMatch(w -> w.contains("재현성 낮음") && !w.contains("원 판단"));
    assertThat(execution.metadata("reproModels")).isEqualTo(List.of("CLAUDE:claude-x"));
    assertThat(execution.metadata("reproComparable")).isNull();
    assertThat(execution.metadata("reproDiffersFromLive")).isNull();
    assertThat(reportText()).doesNotContain("판정 보류").doesNotContain("원 판단(");
  }

  @Test
  @DisplayName("판단 주체 비교는 양쪽이 모두 알려진 항목만 본다 — 원 판단의 모델·공급자를 모르면 다르다고 보지 않는다")
  void judgeComparisonRules() {
    assertThat(WeeklyReviewJob.differentJudge(null, null, LlmProvider.OPENAI, "gpt-x")).as("원 판단 미상").isFalse();
    assertThat(WeeklyReviewJob.differentJudge(null, " ", null, "gpt-x")).as("원 판단 모델 공백").isFalse();
    assertThat(WeeklyReviewJob.differentJudge(LlmProvider.CLAUDE, "same", LlmProvider.OPENAI, "same")).as("공급자가 다름").isTrue();
    assertThat(WeeklyReviewJob.differentJudge(LlmProvider.CLAUDE, "claude-x", null, "claude-x")).as("재실행 공급자 미상·모델 같음").isFalse();
    assertThat(WeeklyReviewJob.differentJudge(null, "claude-x", null, "gpt-x")).as("모델이 다름").isTrue();
    assertThat(WeeklyReviewJob.differentJudge(LlmProvider.OPENAI, "gpt-x", LlmProvider.OPENAI, "gpt-x")).isFalse();

    assertThat(WeeklyReviewJob.judgeLabel(LlmProvider.OPENAI, "gpt-x")).isEqualTo("OPENAI:gpt-x");
    assertThat(WeeklyReviewJob.judgeLabel(null, "gpt-x")).isEqualTo("gpt-x");
    assertThat(WeeklyReviewJob.judgeLabel(LlmProvider.CLAUDE, null)).isEqualTo("CLAUDE");
    assertThat(WeeklyReviewJob.judgeLabel(null, " ")).isEqualTo(WeeklyReviewJob.UNKNOWN_JUDGE);
  }

  @Test
  @DisplayName("note-v1: 같은 기준일의 LIVE·LLM_NOMEM 쌍이 있으면 픽 Jaccard 평균·공통 티커 |Δconviction| 평균을 보고하고, 확정 지연 노트 수를 경보 줄로 낸다")
  void memoryComparisonLines() {
    when(icService.proposeWeightSet(org.mockito.ArgumentMatchers.eq(5), any(), any(), any())).thenReturn(Optional.empty());
    LocalDate d1 = today.minusDays(3);
    LocalDate d2 = today.minusDays(2);
    when(adviceWriter.findRange(any(), any(), any(), org.mockito.ArgumentMatchers.eq(AdviceVariant.LIVE), anyInt())).thenReturn(List.of(
        AdviceHeader.builder().adviceId(10L).baseDate(d1).variant(AdviceVariant.LIVE).build(),
        AdviceHeader.builder().adviceId(11L).baseDate(d2).variant(AdviceVariant.LIVE).build(),
        AdviceHeader.builder().adviceId(12L).baseDate(today.minusDays(1)).variant(AdviceVariant.LIVE).build()));   // NOMEM 없는 날 → 제외
    when(adviceWriter.findRange(any(), any(), any(), org.mockito.ArgumentMatchers.eq(AdviceVariant.LLM_NOMEM), anyInt())).thenReturn(List.of(
        AdviceHeader.builder().adviceId(20L).baseDate(d1).variant(AdviceVariant.LLM_NOMEM).build(),
        AdviceHeader.builder().adviceId(21L).baseDate(d2).variant(AdviceVariant.LLM_NOMEM).build()));
    // d1: {A 0.8, B 0.7} vs {A 0.7, B 0.7, C 0.6} → Jaccard 2/3, |Δ| (0.1+0)/2 ; d2: {A 0.6} vs {A 0.6} → 1, 0
    when(adviceWriter.picks(10L)).thenReturn(List.of(pick("A", 0.8), pick("B", 0.7)));
    when(adviceWriter.picks(20L)).thenReturn(List.of(pick("A", 0.7), pick("B", 0.7), pick("C", 0.6)));
    when(adviceWriter.picks(11L)).thenReturn(List.of(pick("A", 0.6)));
    when(adviceWriter.picks(21L)).thenReturn(List.of(pick("A", 0.6)));
    when(notes.countStaleOpen(any())).thenReturn(3);

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat((Double) execution.metadata("nomemJaccard")).isCloseTo((2.0 / 3 + 1) / 2, org.assertj.core.data.Offset.offset(0.001));
    assertThat((Double) execution.metadata("nomemDeltaConviction")).as("(0.1 + 0 + 0) / 3 공통 티커").isCloseTo(0.1 / 3, org.assertj.core.data.Offset.offset(0.001));
    assertThat(execution.metadata("staleNotes")).isEqualTo(3);
    ArgumentCaptor<kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage> message =
        ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().toBlocks().toString()).contains("LIVE vs NOMEM(최근 4주): 픽 Jaccard 평균 0.83 · |Δconviction| 평균 0.03 (n=2일)")
        .contains("OPEN 노트 10영업일 초과: 3건");
  }

  @Test
  @DisplayName("IC 표본 부족(2026-10-16): 보고에 n_eff·창·IC_BACKFILL 명령을 적고, 활성 세트가 아직 SEED 면 경고, h=20 활성 세트 없음도 경고한다")
  void weightGateExplainsNEffAndBackfill() {
    when(icService.proposeWeightSet(org.mockito.ArgumentMatchers.eq(5), any(), any(), any())).thenReturn(Optional.empty());
    when(icService.learnableNEff(org.mockito.ArgumentMatchers.eq(5), any())).thenReturn(11.0);
    when(icService.learnableNEff(org.mockito.ArgumentMatchers.eq(20), any())).thenReturn(2.75);

    AdvisorExecution execution = execution();
    job.execute(execution);

    ArgumentCaptor<kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage> message =
        ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage.class);
    verify(notifier).publish(message.capture());
    String text = message.getValue().toBlocks().toString();
    assertThat(text).contains("갱신 없음 — n_eff 11.0 < " + properties.getIc().getMinNEff() + " (h=5, 창 " + properties.icWindowDays(5) + "일). 활성 세트 #1(SEED) 유지")
        .contains("POST /api/advisor/admin/jobs/IC_BACKFILL")
        .contains("[h=20] 갱신 없음 — n_eff 2.8 <").contains("IC_BACKFILL?horizon=20");
    assertThat(execution.warnings()).anyMatch(w -> w.contains("SEED") && w.contains("IC_BACKFILL"))
        .anyMatch(w -> w.startsWith("[h=20]") && w.contains("활성 가중치 세트 없음"));
    verify(weightSets, never()).insert(any(), any(Boolean.class));
  }

  private static kr.hvy.blog.modules.advisor.domain.model.PickRow pick(String ticker, double conviction) {
    return kr.hvy.blog.modules.advisor.domain.model.PickRow.builder().ticker(ticker).pickRank(1)
        .direction(kr.hvy.blog.modules.advisor.domain.code.PickDirection.LONG).conviction(conviction).build();
  }

  @Test
  @DisplayName("메모리가 켜지면 보조 모델로 교훈을 제안·검증하고, 가중치 단계 예외는 격리되어 PARTIAL")
  void lessonsWhenMemoryOnAndWeightFailureIsolated() {
    when(adviceWriter.countLivePicks(AdviceKind.DAILY)).thenReturn(500);
    when(icService.proposeWeightSet(org.mockito.ArgumentMatchers.eq(5), any(), any(), any())).thenThrow(new IllegalStateException("ic boom"));
    when(lessonService.review(any())).thenReturn(List.of(9L));
    when(lessonService.cells(any(), any())).thenReturn(List.of());
    when(lessonService.reviewPayload(any(), any(), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(Map.of("cells", List.of()));
    when(lessonService.apply(any(), any(), any(), any())).thenReturn(new LessonService.Applied(List.of(), List.of(), List.of(), List.of()));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.failures()).extracting(AdvisorExecution.Failure::target).contains("STEP:WEIGHTS");
    verify(lessonService).apply(any(), any(), any(), any());
    assertThat(execution.llmCalls()).as("교훈 1회 + 재현성 3회").isEqualTo(4);
    verify(notifier).publish(any());
  }

  private static String picksJson(String tickers) {
    StringBuilder sb = new StringBuilder();
    for (String t : tickers.split(",")) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append("{\"ticker\":").append(t.trim()).append(",\"direction\":\"LONG\",\"conviction\":\"0.70\",\"thesis\":\"t\",\"risk\":\"r\",\"citedFeatures\":[]}");
    }
    return sb.toString();
  }

  private static String frozenPayload() {
    return "{\"asOf\":\"2026-09-11\",\"sectors\":{\"top\":[[\"G2510\",\"반도체\",0.04]],\"bottom\":[]},"
        + "\"candidates\":{\"columns\":[\"tkr\",\"name\",\"sec\"],\"rows\":[[\"T00\",\"a\",\"G2510\"],[\"T01\",\"b\",\"G2510\"],[\"T02\",\"c\",\"G2510\"],[\"T03\",\"d\",\"G2510\"]]}}";
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(500L).jobType(AdvisorJobType.WEEKLY_REVIEW).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(today).build();
    return new AdvisorExecution(run, today, properties);
  }
}
