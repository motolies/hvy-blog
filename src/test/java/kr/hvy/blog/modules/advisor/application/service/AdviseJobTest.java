package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.SignalWeightRow;
import kr.hvy.blog.modules.advisor.domain.model.WeightSet;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.LessonRepository;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.WeightSetRepository;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * 일일 잡 흐름을 고정한다: 게이트 SKIPPED(마감 후 경보), 정상 경로(섀도·LIVE 저장·발행·사용량), 가드 전멸 FAILED(미발행), 채점 실패 격리.
 */
class AdviseJobTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final AdvisorGateService gate = mock(AdvisorGateService.class);
  private final SignalIcService icService = mock(SignalIcService.class);
  private final MarketFeatureService marketFeatures = mock(MarketFeatureService.class);
  private final CandidateScreeningService screening = mock(CandidateScreeningService.class);
  private final WeightSetRepository weightSets = mock(WeightSetRepository.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final PromptInputWriter promptInputs = mock(PromptInputWriter.class);
  private final LessonRepository lessons = mock(LessonRepository.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<AdviseJob.ScoreHook> hookProvider = mock(ObjectProvider.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<NewsFeatureService> newsProvider = mock(ObjectProvider.class);
  private final NewsFeatureService newsFeatures = mock(NewsFeatureService.class);
  private final RecentOutcomesService recentOutcomes = mock(RecentOutcomesService.class);
  private final LocalDate base = LocalDate.of(2026, 9, 11);
  private final AtomicInteger llmCalls = new AtomicInteger();
  /** 후보 T00~T02 를 고르고 T00 은 입력 특징(r20=0.012345)을 허용오차 안에서 인용 */
  static final String REPLY = """
      {"regime":{"code":"RISK_ON","kospiDir":"UP","kosdaqDir":"NEUTRAL","pUp":"0.70","rationale":"근거"},
       "trendOutlook":{"kospi":{"persist":"BEYOND_20D","confidence":"0.70","invalidation":"BELOW_MA20"},
                       "kosdaq":{"persist":"WITHIN_5D","confidence":"0.60","invalidation":"NONE"}},
       "sectors":[{"code":"G2510","reason":"반도체"}],
       "picks":[{"ticker":"T00","direction":"LONG","conviction":"0.80","thesis":"t","risk":"r","citedFeatures":[{"name":"r20","value":0.0123}]},
                {"ticker":"T01","direction":"LONG","conviction":"0.65","thesis":"t","risk":"r","citedFeatures":[]},
                {"ticker":"T02","direction":"AVOID","conviction":"0.60","thesis":"t","risk":"r","citedFeatures":[]}],
       "summary":"요약"}
      """;
  private String llmReply = REPLY;

  private AdviseJob job;

  @BeforeEach
  void setUp() {
    ChatModel stub = prompt -> {
      llmCalls.incrementAndGet();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(llmReply))),
          ChatResponseMetadata.builder().id("resp").model("judge-x").usage(new DefaultUsage(6000, 1900)).build());
    };
    job = new AdviseJob(properties, gate, icService, marketFeatures, screening, weightSets, new AdvicePromptBuilder(properties), new PromptResources(),
        new MarketJudgeClient(ChatClient.create(stub), "judge-x"), adviceWriter, promptInputs, lessons, notifier, hookProvider, newsProvider,
        recentOutcomes);
    when(hookProvider.getIfAvailable()).thenReturn(null);
    when(newsProvider.getIfAvailable()).thenReturn(newsFeatures);
    when(recentOutcomes.block(any())).thenReturn(Optional.empty());
    when(adviceWriter.firstNewsAdviceDate()).thenReturn(Optional.empty());
    when(adviceWriter.firstMemoryAdviceDate()).thenReturn(Optional.empty());
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, false, true, false, DataQuality.OK, "DAILY 완료"));
    when(icService.computeIncremental(any())).thenReturn(Optional.empty());
    when(marketFeatures.features(base)).thenReturn(AdvicePromptBuilderTest.market());
    when(screening.screen(base)).thenReturn(AdvicePromptBuilderTest.screening(8));
    when(weightSets.find(1L)).thenReturn(Optional.of(WeightSet.builder().weightSetId(1L).source(WeightSetSource.SEED).active(true)
        .weights(List.of(SignalWeightRow.builder().signalCode("MOM_20D").baseWeight(0.12).multiplier(1).weight(0.12).enabled(true).build())).build()));
    when(adviceWriter.find(any(), anyString(), any())).thenReturn(Optional.empty());
    when(adviceWriter.findLatest(any(), any())).thenReturn(Optional.empty());
    when(adviceWriter.countLivePicks()).thenReturn(0);
    when(adviceWriter.insertHeader(any())).thenReturn(842L, 843L, 844L);
    when(notifier.publish(any())).thenReturn(true);
  }

  @Test
  @DisplayName("게이트 미충족이면 SKIPPED 로 끝나고, 마감 후에는 #hvy-error 경보를 보낸다")
  void gateSkipsAndAlertsAfterDeadline() {
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, false, false, true, DataQuality.OK, "DAILY 미완료"));
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.isSkipped()).isTrue();
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SKIPPED);
    verify(notifier).alert(org.mockito.ArgumentMatchers.contains("미실행"), eq(true));
    verify(screening, never()).screen(any());
    assertThat(llmCalls.get()).isZero();
  }

  @Test
  @DisplayName("정상 경로: 정량 섀도 + LIVE 저장 + 입력 스냅샷 + 발행 + 사용량, 메모리 미활성이라 LLM 1회")
  void happyPath() {
    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).isEqualTo(1);
    assertThat(execution.llmCalls()).isEqualTo(1);
    assertThat(execution.promptTokens()).isEqualTo(6000);
    assertThat(execution.model()).isEqualTo("judge-x");

    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(headers.capture());
    assertThat(headers.getAllValues()).extracting(AdviceHeader::variant).containsExactly(AdviceVariant.QUANT_TOPN, AdviceVariant.LIVE);
    AdviceHeader live = headers.getAllValues().get(1);
    assertThat(live.model()).isEqualTo("judge-x");
    assertThat(live.weightSetId()).isEqualTo(1L);
    assertThat(live.leadingSectors()).hasSize(1);
    assertThat(live.leadingSectors().getFirst().consistent()).as("advice-v6: 섹터 맥락(SectorContext)의 consistent 가 주도 섹터 콜에 실린다").isTrue();
    assertThat(live.promptVersion()).isEqualTo(PromptResources.ADVICE_VERSION).isEqualTo("advice-v6");
    assertThat(live.guard()).as("T00 은 secCons=1·비과열이라 클램프 없음").doesNotContainKeys("capNonConsistent", "capOverheated");
    assertThat(live.trendKospi()).as("규칙 추세는 시장 특징에서").isEqualTo(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BULL);
    assertThat(live.trendKosdaq()).as("KOSDAQ 추세 없음(픽스처)").isNull();
    assertThat(live.outlooks()).hasSize(2);
    assertThat(live.outlookOf("0001").persist()).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.TrendHorizon.BEYOND_20D);
    assertThat(live.outlookOf("0001").invalidation()).isEqualTo(kr.hvy.blog.modules.advisor.domain.code.InvalidationType.BELOW_MA20);
    assertThat(live.entryDate()).isEqualTo(LocalDate.of(2026, 9, 14));
    assertThat(live.exitDate()).isEqualTo(LocalDate.of(2026, 9, 18));
    assertThat(live.dataAsOf()).containsEntry("global", "2026-09-10");
    AdviceHeader quant = headers.getAllValues().get(0);
    assertThat(quant.trendKospi()).as("정량 섀도도 추세 상태를 남긴다(KPI 절단용)").isEqualTo(kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode.BULL);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertPicks(anyLong(), picks.capture());
    assertThat(picks.getAllValues().get(0)).as("정량 섀도 top-N").hasSize(properties.getShadow().getQuantTopN());
    assertThat(picks.getAllValues().get(1)).extracting(PickRow::ticker).containsExactly("T00", "T01", "T02");
    ArgumentCaptor<kr.hvy.blog.modules.advisor.domain.model.PromptInputRow> inputs = ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.domain.model.PromptInputRow.class);
    verify(promptInputs).upsert(inputs.capture());
    verify(notifier).publish(any(SlackMessage.class));
    verify(adviceWriter).markPublished(eq(843L), any());
    assertThat(execution.metadata("adviceId")).isEqualTo(843L);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name)
        .contains("IC", "FEATURES", "SCREEN", "SHADOW_QUANT", "NOTES", "JUDGE", "SAVE", "PUBLISH", "SHADOW_NOMEM");
    // note-v1: 확정 노트 게이트 미달(empty) → 블록 없음·memory_json null·NOMEM 은 "메모리 미주입" 으로 건너뛴다
    assertThat(inputs.getValue().userPayload()).doesNotContain("recentOutcomes");
    assertThat(live.memoryJson()).isNull();
    assertThat(execution.metadata("skip.NOTES")).isEqualTo("확정 노트 < " + properties.getNote().getMinFinalized());
    assertThat(execution.metadata("skip.SHADOW_NOMEM")).isEqualTo("메모리 미주입");
    assertThat(execution.metadata("memory")).isNull();
  }

  @Test
  @DisplayName("note-v1: recentOutcomes 가 있으면 LIVE 프롬프트에 실리고 헤더 memory_json 이 채워지며, 메모리 없는 섀도(LLM_NOMEM)가 한 번 더 돌아 LLM 2회")
  void recentOutcomesInjectedRunsNomemShadow() {
    when(recentOutcomes.block(base)).thenReturn(Optional.of(outcomesBlock()));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("LIVE + LLM_NOMEM").isEqualTo(2);
    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(3)).insertHeader(headers.capture());
    assertThat(headers.getAllValues()).extracting(AdviceHeader::variant).containsExactly(AdviceVariant.QUANT_TOPN, AdviceVariant.LIVE, AdviceVariant.LLM_NOMEM);
    AdviceHeader live = headers.getAllValues().get(1);
    assertThat(live.memoryJson()).containsEntry("recentOutcomes", 2).containsEntry("lessons", List.of()).containsEntry("scoreboard", false);
    assertThat(headers.getAllValues().get(2).memoryJson()).as("NOMEM 섀도는 메모리 없음 → null").isNull();
    ArgumentCaptor<kr.hvy.blog.modules.advisor.domain.model.PromptInputRow> inputs = ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.domain.model.PromptInputRow.class);
    verify(promptInputs, org.mockito.Mockito.times(2)).upsert(inputs.capture());
    assertThat(inputs.getAllValues().get(0).variant()).isEqualTo(AdviceVariant.LIVE);
    assertThat(inputs.getAllValues().get(0).userPayload()).contains("\"recentOutcomes\":{\"windowTradingDays\":20").contains("\"IDIOSYNCRATIC\",1,14,");
    assertThat(inputs.getAllValues().get(1).variant()).isEqualTo(AdviceVariant.LLM_NOMEM);
    assertThat(inputs.getAllValues().get(1).userPayload()).as("NOMEM 프롬프트에는 빈도표가 없다").doesNotContain("recentOutcomes");
    assertThat(execution.metadata("memory")).isEqualTo(live.memoryJson());
    assertThat(execution.metadata("shadowNomemAdviceId")).isEqualTo(844L);
    assertThat(execution.metadata("skip.NOTES")).isNull();
    assertThat(execution.metadata("memoryOn")).as("300 게이트(느린 층)는 그대로 꺼져 있다").isEqualTo(false);
  }

  @Test
  @DisplayName("note-v1: 메모리가 처음 실린 LIVE 판단이 nomem-weeks 보다 오래됐으면 NOMEM 섀도는 '섀도 기간 종료' 로 건너뛴다 (nomem-weeks 미사용 결함 수정)")
  void nomemShadowClosesAfterWeeks() {
    when(recentOutcomes.block(base)).thenReturn(Optional.of(outcomesBlock()));
    when(adviceWriter.firstMemoryAdviceDate()).thenReturn(Optional.of(base.minusWeeks(properties.getShadow().getNomemWeeks()).minusDays(1)));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("LIVE 만").isEqualTo(1);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(any());
    assertThat(execution.metadata("skip.SHADOW_NOMEM")).isEqualTo("섀도 기간 종료");
    assertThat(execution.metadata("memory")).isNotNull();

    // 창 경계: 첫 메모리 판단일 + nomem-weeks 당일까지는 열려 있고, 첫 판단이 없으면(오늘이 처음) 열린다
    when(adviceWriter.firstMemoryAdviceDate()).thenReturn(Optional.of(base.minusWeeks(properties.getShadow().getNomemWeeks())));
    assertThat(job.nomemShadowOpen(base)).isTrue();
    when(adviceWriter.firstMemoryAdviceDate()).thenReturn(Optional.empty());
    assertThat(job.nomemShadowOpen(base)).isTrue();
  }

  /** RecentOutcomesService 가 만든 형식의 빈도표 2행 */
  private static java.util.Map<String, Object> outcomesBlock() {
    java.util.Map<String, Object> block = new java.util.LinkedHashMap<>();
    block.put("windowTradingDays", 20);
    block.put("finalizedAsOf", "2026-09-11");
    block.put("columns", List.of("class", "secCons", "n", "confirmRate", "meanFinalExcess", "se", "underpowered"));
    block.put("rows", List.of(List.of("IDIOSYNCRATIC", 1, 14, 0.64, -0.0121, 0.0048, true), List.of("ON_TRACK", 1, 22, 0.55, 0.0031, 0.0039, true)));
    return block;
  }

  @Test
  @DisplayName("뉴스가 켜지면 news 블록·citedNews 스키마가 붙고, 뉴스 없는 섀도(LLM_NONEWS)가 한 번 더 돌아 LLM 2회·헤더 3개가 된다")
  void newsEnabledAddsNonewsShadow() {
    properties.getNews().setEnabled(true);
    kr.hvy.blog.modules.advisor.domain.model.NewsBlock block = new kr.hvy.blog.modules.advisor.domain.model.NewsBlock(
        java.time.Instant.parse("2026-09-11T10:30:00Z"), 36,
        List.of(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N1", "09-11 16:20", "외국인 이틀째 순매수", List.of())),
        java.util.Map.of("T00", List.of(new kr.hvy.blog.modules.advisor.domain.model.NewsBlock.Headline("N2", "09-11 08:40", "종목0 신제품 발표", List.of("T00")))));
    when(newsFeatures.news(eq(base), any())).thenReturn(Optional.of(block));
    llmReply = REPLY.replace("\"citedFeatures\":[{\"name\":\"r20\",\"value\":0.0123}]", "\"citedFeatures\":[{\"name\":\"r20\",\"value\":0.0123}],\"citedNews\":[\"N2\",\"N1\",\"N9\"]");

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("LIVE + LLM_NONEWS").isEqualTo(2);
    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(3)).insertHeader(headers.capture());
    assertThat(headers.getAllValues()).extracting(AdviceHeader::variant).containsExactly(AdviceVariant.QUANT_TOPN, AdviceVariant.LIVE, AdviceVariant.LLM_NONEWS);
    AdviceHeader live = headers.getAllValues().get(1);
    assertThat(live.newsIds()).containsExactly("N1", "N2");
    assertThat(headers.getAllValues().get(2).newsIds()).as("뉴스 없는 섀도").isEmpty();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter, org.mockito.Mockito.times(3)).insertPicks(anyLong(), picks.capture());
    PickRow t00 = picks.getAllValues().get(1).stream().filter(p -> p.ticker().equals("T00")).findFirst().orElseThrow();
    assertThat(t00.citedNews()).as("입력에 없던 N9 는 제거").containsExactly("N2", "N1");
    assertThat(live.guard()).containsEntry("unknownNews", 1);
    assertThat(execution.metadata("newsIds")).isEqualTo(2);
    assertThat(execution.metadata("shadowNonewsAdviceId")).isEqualTo(844L);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name).contains("NEWS", "SHADOW_NONEWS");
  }

  @Test
  @DisplayName("가드 통과 픽이 최소 미만이면 예외로 FAILED — 발행하지 않고 입력 스냅샷만 남긴다")
  void tooFewPicksFails() {
    llmReply = REPLY.replace("\"T0", "\"ZZ"); // 후보 밖 티커 → 전부 제거
    AdvisorExecution execution = execution();
    assertThatThrownBy(() -> job.execute(execution)).isInstanceOf(IllegalStateException.class).hasMessageContaining("최소");
    verify(notifier, never()).publish(any());
    verify(promptInputs).upsert(any());
    verify(adviceWriter, never()).insertPicks(eq(843L), any());
  }

  @Test
  @DisplayName("채점 훅·IC 가 죽어도 판단은 진행하고 run 은 PARTIAL")
  void scoringFailureIsIsolated() {
    when(hookProvider.getIfAvailable()).thenReturn(e -> {
      throw new IllegalStateException("scoring boom");
    });
    when(icService.computeIncremental(any())).thenThrow(new IllegalStateException("ic boom"));
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.failures()).extracting(AdvisorExecution.Failure::target).contains("STEP:SCORE", "STEP:IC");
    verify(notifier).publish(any(SlackMessage.class));
  }

  @Test
  @DisplayName("IC 증분이 상한에 잘리면 경고와 icGapFrom 메타를 남기되 run 상태는 SUCCESS 그대로")
  void icGapIsWarnedNotFailed() {
    when(icService.computeIncremental(any())).thenReturn(Optional.of(
        new SignalIcService.IncrementalResult(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 9, 4), LocalDate.of(2020, 1, 1), 480, 2)));
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(execution.metadata("icRange")).isEqualTo("2026-07-28~2026-09-04");
    assertThat(execution.metadata("icGapFrom")).isEqualTo("2020-01-01");
    assertThat(execution.metadata("icChunks")).isEqualTo(2);
    assertThat(execution.warnings()).anySatisfy(w -> assertThat(w).contains("IC 공백 2020-01-01~2026-07-27").contains("IC_BACKFILL?baseDate=2020-01-01"));
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(1284L).jobType(AdvisorJobType.ADVISE).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }
}
