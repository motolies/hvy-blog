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
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
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
  private WeeklyReviewJob job;

  @BeforeEach
  void setUp() {
    ChatModel judgeStub = prompt -> {
      int n = judgeCalls.incrementAndGet();
      String picks = n == 2 ? "\"T00\",\"T01\",\"T03\"" : "\"T00\",\"T01\",\"T02\"";
      String reply = "{\"regime\":{\"code\":\"RISK_ON\",\"kospiDir\":\"UP\",\"kosdaqDir\":\"NEUTRAL\",\"pUp\":\"0.70\",\"rationale\":\"r\"},"
          + "\"sectors\":[{\"code\":\"G2510\",\"reason\":\"s\"}],\"picks\":[" + picksJson(picks) + "],\"summary\":\"요약\"}";
      return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    };
    ChatModel assistStub = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("{\"proposals\":[],\"nullResults\":[],\"retireCandidates\":[]}"))));
    job = new WeeklyReviewJob(properties, scoreJob, icService, weightSets, lessonService, kpi, adviceWriter, promptInputs, new PromptResources(),
        new MarketJudgeClient(ChatClient.create(judgeStub), "judge"), new MarketJudgeClient(ChatClient.create(assistStub), "assist"), notifier, jdbc,
        notes, calendar);
    when(scoreJob.scoreDue(any())).thenReturn(AdviseJob.Scoreboard.empty());
    when(calendar.previousTradingDays(any(), anyInt())).thenReturn(List.of(today.minusDays(14)));
    when(notes.countStaleOpen(any())).thenReturn(0);
    when(adviceWriter.findRange(any(), any(), any(), anyInt())).thenReturn(List.of());
    when(icService.computeIncremental(any())).thenReturn(Optional.empty());
    when(icService.latestScorableDate(anyInt())).thenReturn(Optional.of(today.minusDays(7)));
    WeightSet active = WeightSet.builder().weightSetId(1L).source(WeightSetSource.SEED).active(true).weights(List.of(
        SignalWeightRow.builder().signalCode("MOM_20D").baseWeight(0.12).multiplier(1).weight(0.12).enabled(true).build(),
        SignalWeightRow.builder().signalCode("TV_SURGE").baseWeight(0.10).multiplier(1).weight(0.10).enabled(true).build())).build();
    when(weightSets.active()).thenReturn(Optional.of(active));
    when(weightSets.insert(any(), any(Boolean.class))).thenReturn(2L);
    when(kpi.variantSummaries(any(), any())).thenReturn(List.of(
        new AdvisorKpiService.VariantSummary(AdviceVariant.LIVE, 10, 60, 0.55, 0.006, 0.004, 0.003, 0.002, 0.004, null, 0)));
    when(kpi.regimeSummary(any(), any(), any())).thenReturn(new AdvisorKpiService.RegimeSummary(10, 0.6, 0.22, 0.12));
    when(kpi.trendSummary(any(), any(), any())).thenReturn(new AdvisorKpiService.TrendSummary(0, null, null, 0, null));
    when(kpi.morningSummary(any(), any(), any())).thenReturn(new AdvisorKpiService.MorningSummary(0, null, null));
    when(kpi.calibration(any(), any())).thenReturn(List.of(new AdvisorKpiService.CalibrationRow(0.7, 20, 0.55, 0.004)));
    when(adviceWriter.countLivePicks()).thenReturn(0);
    when(adviceWriter.findLatest(any(), any())).thenReturn(Optional.of(AdviceHeader.builder().adviceId(842L).runId(77L).baseDate(today.minusDays(2)).build()));
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
    when(icService.proposeWeightSet(any(), any(), any())).thenReturn(Optional.of(proposed));

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
    verify(notifier).publish(any());
    assertThat(execution.metadata("promptInputsDeleted")).isEqualTo(3);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name)
        .containsExactly("SCORE", "IC", "WEIGHTS", "LESSONS", "REPRO", "KPI", "MEMORY", "REPORT", "CLEANUP");
    assertThat(execution.steps().get(3).status()).isEqualTo("SKIPPED");
    // note-v1: NOMEM 쌍이 없으면 "쌍 없음", 확정 지연 노트 0건
    ArgumentCaptor<kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage> message =
        ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.application.slack.WeeklyReviewMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().toBlocks().toString()).contains("LIVE vs NOMEM(최근 4주): 쌍 없음").contains("OPEN 노트 10영업일 초과: 0건");
    verify(notes).countStaleOpen(today.minusDays(14));
  }

  @Test
  @DisplayName("note-v1: 같은 기준일의 LIVE·LLM_NOMEM 쌍이 있으면 픽 Jaccard 평균·공통 티커 |Δconviction| 평균을 보고하고, 확정 지연 노트 수를 경보 줄로 낸다")
  void memoryComparisonLines() {
    when(icService.proposeWeightSet(any(), any(), any())).thenReturn(Optional.empty());
    LocalDate d1 = today.minusDays(3);
    LocalDate d2 = today.minusDays(2);
    when(adviceWriter.findRange(any(), any(), org.mockito.ArgumentMatchers.eq(AdviceVariant.LIVE), anyInt())).thenReturn(List.of(
        AdviceHeader.builder().adviceId(10L).baseDate(d1).variant(AdviceVariant.LIVE).build(),
        AdviceHeader.builder().adviceId(11L).baseDate(d2).variant(AdviceVariant.LIVE).build(),
        AdviceHeader.builder().adviceId(12L).baseDate(today.minusDays(1)).variant(AdviceVariant.LIVE).build()));   // NOMEM 없는 날 → 제외
    when(adviceWriter.findRange(any(), any(), org.mockito.ArgumentMatchers.eq(AdviceVariant.LLM_NOMEM), anyInt())).thenReturn(List.of(
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

  private static kr.hvy.blog.modules.advisor.domain.model.PickRow pick(String ticker, double conviction) {
    return kr.hvy.blog.modules.advisor.domain.model.PickRow.builder().ticker(ticker).pickRank(1)
        .direction(kr.hvy.blog.modules.advisor.domain.code.PickDirection.LONG).conviction(conviction).build();
  }

  @Test
  @DisplayName("메모리가 켜지면 보조 모델로 교훈을 제안·검증하고, 가중치 단계 예외는 격리되어 PARTIAL")
  void lessonsWhenMemoryOnAndWeightFailureIsolated() {
    when(adviceWriter.countLivePicks()).thenReturn(500);
    when(icService.proposeWeightSet(any(), any(), any())).thenThrow(new IllegalStateException("ic boom"));
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
