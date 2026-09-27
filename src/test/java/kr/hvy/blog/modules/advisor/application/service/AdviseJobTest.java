package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.PickUniverse;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.WeightSetSource;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
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
  /** 후보 T00~T02 를 고르고 T00 은 입력 특징(r20=0.012345)을 허용오차 안에서 인용. 매수 전용(advice-v9) 스키마라 direction 이 없다 */
  static final String REPLY = """
      {"regime":{"code":"RISK_ON","kospiDir":"UP","kosdaqDir":"NEUTRAL","pUp":"0.70","rationale":"근거"},
       "trendOutlook":{"kospi":{"persist":"BEYOND_20D","confidence":"0.70","invalidation":"BELOW_MA20"},
                       "kosdaq":{"persist":"WITHIN_5D","confidence":"0.60","invalidation":"NONE"}},
       "sectors":[{"code":"G2510","reason":"반도체"}],
       "picks":[{"ticker":"T00","conviction":"0.80","thesis":"t","risk":"r","citedFeatures":[{"name":"r20","value":0.0123}]},
                {"ticker":"T01","conviction":"0.65","thesis":"t","risk":"r","citedFeatures":[]},
                {"ticker":"T02","conviction":"0.60","thesis":"t","risk":"r","citedFeatures":[]}],
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
    // 기존 흐름 단언(헤더 수·advice id 순서)은 BROAD 섀도 없이 본다 — 기본값 KOSPI200 의 BROAD 섀도는 kospi200AddsBroadShadow 가 따로 본다
    properties.setPickUniverse(PickUniverse.ALL);
    when(hookProvider.getIfAvailable()).thenReturn(null);
    when(newsProvider.getIfAvailable()).thenReturn(newsFeatures);
    when(recentOutcomes.block(any())).thenReturn(Optional.empty());
    when(adviceWriter.firstNewsAdviceDate(AdviceKind.DAILY)).thenReturn(Optional.empty());
    when(adviceWriter.firstMemoryAdviceDate(AdviceKind.DAILY)).thenReturn(Optional.empty());
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, false, true, false, DataQuality.OK, "DAILY 완료"));
    when(icService.computeIncremental(any())).thenReturn(List.of());
    when(marketFeatures.features(base)).thenReturn(AdvicePromptBuilderTest.market());
    when(screening.screen(base)).thenReturn(AdvicePromptBuilderTest.screening(8));
    when(weightSets.find(1L)).thenReturn(Optional.of(WeightSet.builder().weightSetId(1L).source(WeightSetSource.SEED).active(true)
        .weights(List.of(SignalWeightRow.builder().signalCode("MOM_20D").baseWeight(0.12).multiplier(1).weight(0.12).enabled(true).build())).build()));
    when(adviceWriter.find(any(), any(), any())).thenReturn(Optional.empty());
    when(adviceWriter.findLatest(any(), any(), any())).thenReturn(Optional.empty());
    when(adviceWriter.countLivePicks(AdviceKind.DAILY)).thenReturn(0);
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
    assertThat(live.promptVersion()).isEqualTo(PromptResources.ADVICE_VERSION).isEqualTo("advice-v9");
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
  @DisplayName("M6: 시장 특징의 합성 국면은 LIVE 헤더 regime_json 으로 저장되고 가드가 그 정책 한도를 적용해 guard_json.policy 에 남긴다 — 정량 섀도는 국면·정책 없음")
  void regimeSavedAndPolicyApplied() {
    MarketFeatures m = AdvicePromptBuilderTest.market();
    MarketRegime regime = new MarketRegime("0001", m.asOf(), MarketTrendCode.BEAR, -3, VolRegimeCode.HIGH, 0.9, 0.02, 1200,
        new RegimePolicy(properties).limits(MarketTrendCode.BEAR, VolRegimeCode.HIGH), List.of());
    when(marketFeatures.features(base)).thenReturn(new MarketFeatures(m.asOf(), m.indices(), m.flows(), m.global(), m.topSectors(),
        m.bottomSectors(), m.sigma5d(), m.globalAsOf(), m.globalAgeTradingDays(), m.flowAsOf(), m.sectorAsOf(), m.entryDate(),
        m.exitDate(), m.trends(), m.links(), m.sectorIndexAsOf(), regime));
    AdvisorExecution execution = execution();
    job.execute(execution);

    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(headers.capture());
    AdviceHeader quant = headers.getAllValues().get(0);
    AdviceHeader live = headers.getAllValues().get(1);
    assertThat(live.regime()).isEqualTo(regime);
    assertThat(quant.regime()).as("정량 섀도는 대조군 — 정책 미적용·국면 미저장").isNull();
    assertThat(live.guard()).containsKey("policy");
    @SuppressWarnings("unchecked")
    Map<String, Object> applied = (Map<String, Object>) live.guard().get("policy");
    assertThat(applied).containsEntry("version", "regime-policy-v2").containsEntry("longMax", 8).containsEntry("convictionCap", 0.65);
    assertThat(execution.metadata("regime")).isEqualTo("BEAR·HIGH");
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
    when(adviceWriter.firstMemoryAdviceDate(AdviceKind.DAILY)).thenReturn(Optional.of(base.minusWeeks(properties.getShadow().getNomemWeeks()).minusDays(1)));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("LIVE 만").isEqualTo(1);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(any());
    assertThat(execution.metadata("skip.SHADOW_NOMEM")).isEqualTo("섀도 기간 종료");
    assertThat(execution.metadata("memory")).isNotNull();

    // 창 경계: 첫 메모리 판단일 + nomem-weeks 당일까지는 열려 있고, 첫 판단이 없으면(오늘이 처음) 열린다
    when(adviceWriter.firstMemoryAdviceDate(AdviceKind.DAILY)).thenReturn(Optional.of(base.minusWeeks(properties.getShadow().getNomemWeeks())));
    assertThat(job.nomemShadowOpen(base)).isTrue();
    when(adviceWriter.firstMemoryAdviceDate(AdviceKind.DAILY)).thenReturn(Optional.empty());
    assertThat(job.nomemShadowOpen(base)).isTrue();
  }

  @Test
  @DisplayName("수시 판단(ADHOC): DAILY 가 이미 있는 날도 돌고, 채점·IC·정량 섀도·LLM 섀도·교훈 카운트 없이 LIVE 1건을 kind=ADHOC 로 저장·발행한다")
  void adhocSavesOnlyLiveWithAdhocKind() {
    // 같은 기준일 DAILY 가 이미 있다(alreadyDone) — DAILY 게이트는 막지만 수시 판단은 막지 않는다
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, true, true, false, DataQuality.OK, "이미 판단이 있습니다"));
    when(recentOutcomes.block(base)).thenReturn(Optional.of(outcomesBlock()));   // 메모리가 실려도 NOMEM 섀도는 돌지 않아야 한다
    properties.getNews().setEnabled(false);
    AdviseJob.ScoreHook hook = mock(AdviseJob.ScoreHook.class);
    when(hookProvider.getIfAvailable()).thenReturn(hook);

    AdvisorExecution execution = adhocExecution();
    new AdhocAdviseJob(job).execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("LIVE 1회만").isEqualTo(1);
    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter).insertHeader(headers.capture());
    assertThat(headers.getValue().adviceKind()).isEqualTo(AdviceKind.ADHOC);
    assertThat(headers.getValue().variant()).isEqualTo(AdviceVariant.LIVE);
    verify(hook, never()).scoreDue(any());
    verify(icService, never()).computeIncremental(any());
    verify(lessons, never()).addApplied(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    ArgumentCaptor<SlackMessage> message = ArgumentCaptor.forClass(SlackMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().getFallbackText()).contains("수시 판단");
    assertThat(execution.metadata("adviceKind")).isEqualTo("ADHOC");
    assertThat(execution.metadata("skip.SHADOW_QUANT")).isEqualTo(AdviseJob.ADHOC_SKIP);
    assertThat(execution.metadata("skip.SHADOW_NOMEM")).isEqualTo(AdviseJob.ADHOC_SKIP);
    assertThat(execution.metadata("skip.SCORE")).isEqualTo(AdviseJob.ADHOC_SKIP);
  }

  @Test
  @DisplayName("수시 판단(ADHOC): 같은 기준일 ADHOC 가 이미 있거나 입력이 준비되지 않았으면 LLM 없이 SKIPPED")
  void adhocSkipsWhenExistsOrNotReady() {
    when(adviceWriter.find(base, AdviceKind.ADHOC, AdviceVariant.LIVE)).thenReturn(Optional.of(AdviceHeader.builder().adviceId(77L).build()));
    AdvisorExecution existing = adhocExecution();
    new AdhocAdviseJob(job).execute(existing);
    assertThat(existing.isSkipped()).isTrue();
    assertThat(existing.skipReason()).contains("advice=77");

    when(adviceWriter.find(base, AdviceKind.ADHOC, AdviceVariant.LIVE)).thenReturn(Optional.empty());
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, false, false, true, DataQuality.OK, "DAILY 미완료"));
    AdvisorExecution notReady = adhocExecution();
    new AdhocAdviseJob(job).execute(notReady);
    assertThat(notReady.isSkipped()).isTrue();
    verify(notifier, never()).alert(any(), org.mockito.ArgumentMatchers.anyBoolean());   // 수시 판단은 마감 경보를 울리지 않는다
    assertThat(llmCalls.get()).isZero();
    verify(adviceWriter, never()).insertHeader(any());
  }

  private AdvisorExecution adhocExecution() {
    AdvisorRun run = AdvisorRun.builder().runId(1300L).jobType(AdvisorJobType.ADVISE_ADHOC).triggerType(AdvisorTriggerType.CHAT).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }

  @Test
  @DisplayName("advice-v7: 픽 유니버스가 KOSPI200 이면 필터 없는(ALL) 스크리닝으로 QUANT_TOPN_BROAD 섀도를 LLM 없이 하나 더 저장하고, Slack 에 유니버스 라벨을 붙인다")
  void kospi200AddsBroadShadow() {
    properties.setPickUniverse(PickUniverse.KOSPI200);
    when(screening.screen(base, PickUniverse.ALL)).thenReturn(AdvicePromptBuilderTest.screening(10));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("BROAD 는 비용 0").isEqualTo(1);
    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(3)).insertHeader(headers.capture());
    assertThat(headers.getAllValues()).extracting(AdviceHeader::variant)
        .containsExactly(AdviceVariant.QUANT_TOPN, AdviceVariant.QUANT_TOPN_BROAD, AdviceVariant.LIVE);
    AdviceHeader broad = headers.getAllValues().get(1);
    assertThat(broad.adviceKind()).isEqualTo(AdviceKind.DAILY);
    assertThat(broad.model()).isEqualTo("quant-top-" + properties.getShadow().getQuantTopN());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<kr.hvy.blog.modules.advisor.domain.model.CandidateRow>> candidates = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter, org.mockito.Mockito.times(3)).insertCandidates(anyLong(), candidates.capture());
    assertThat(candidates.getAllValues().get(1)).as("BROAD 후보는 ALL 스크리닝 결과").hasSize(10);
    assertThat(execution.metadata("shadowQuantBroadAdviceId")).isEqualTo(843L);
    assertThat(execution.metadata("pickUniverse")).isEqualTo("KOSPI200");
    ArgumentCaptor<SlackMessage> message = ArgumentCaptor.forClass(SlackMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().toBlocks().toString()).contains("유니버스: KOSPI200");
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
  @DisplayName("매수 전용: 모델이 빈 picks(관망)를 내면 FAILED 가 아니라 픽 0행 헤더로 저장·발행하고 SUCCESS — 메타 abstain=true")
  void abstainIsSavedAndPublished() {
    llmReply = REPLY.replaceAll("(?s)\"picks\":\\[.*?\\],\\s*\"summary\"", "\"picks\":[], \"summary\"");
    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(execution.metadata("abstain")).isEqualTo(true);
    assertThat(execution.metadata("picks")).isEqualTo(0);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter).insertPicks(eq(843L), picks.capture());
    assertThat(picks.getValue()).isEmpty();
    verify(promptInputs).upsert(any());
    ArgumentCaptor<SlackMessage> published = ArgumentCaptor.forClass(SlackMessage.class);
    verify(notifier).publish(published.capture());
    assertThat(published.getValue().getFallbackText()).contains(kr.hvy.blog.modules.advisor.application.slack.DailyAdviceMessage.ABSTAIN_TEXT);
    verify(adviceWriter).markPublished(eq(843L), any());
  }

  @Test
  @DisplayName("가드가 픽을 전부 지워 0픽이 되어도 관망으로 저장·발행한다 — 제거율 100% 라 PARTIAL(모델·프롬프트 점검 신호)")
  void allRemovedIsPartialButPublished() {
    llmReply = REPLY.replace("\"T0", "\"ZZ"); // 후보 밖 티커 → 전부 제거
    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.metadata("abstain")).isEqualTo(true);
    verify(adviceWriter).insertPicks(eq(843L), eq(List.of()));
    verify(notifier).publish(any(SlackMessage.class));
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
    when(icService.computeIncremental(any())).thenReturn(List.of(
        new SignalIcService.IncrementalResult(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 9, 4), LocalDate.of(2020, 1, 1), 480, 2, 5),
        new SignalIcService.IncrementalResult(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 8, 5), LocalDate.of(2020, 1, 1), 300, 2, 20)));
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(execution.metadata("icRange")).isEqualTo("2026-07-28~2026-09-04");
    assertThat(execution.metadata("icGapFrom")).isEqualTo("2020-01-01");
    assertThat(execution.metadata("icChunks")).isEqualTo(2);
    assertThat(execution.warnings()).anySatisfy(w -> assertThat(w).contains("IC 공백 2020-01-01~2026-07-27").contains("IC_BACKFILL?baseDate=2020-01-01 "));
    // M5: DAILY 결정 호라이즌(5)은 기존 키, 그 밖(20)은 "@h" 접미 키와 horizon 파라미터가 붙은 보충 명령
    assertThat(execution.metadata("icRange@20")).isEqualTo("2026-06-20~2026-08-05");
    assertThat(execution.metadata("icGapFrom@20")).isEqualTo("2020-01-01");
    assertThat(execution.warnings()).anySatisfy(w -> assertThat(w).contains("IC 공백(h=20)").contains("IC_BACKFILL?baseDate=2020-01-01&horizon=20"));
  }

  // ----- M7 H20 (주간 20거래일) -----

  /** H20 경로 스텁: H20 활성 세트·20거래일 창 특징(regime 포함)·H20 스크리닝 */
  private void stubH20(MarketRegime regime) {
    WeightSet h20Set = WeightSet.builder().weightSetId(2L).source(WeightSetSource.SEED).active(true)
        .weights(List.of(SignalWeightRow.builder().signalCode("MOM_60D").baseWeight(0.10).multiplier(1).weight(0.10).enabled(true).build())).build();
    when(screening.weightSetFor(AdviceKind.H20)).thenReturn(Optional.of(h20Set));
    MarketFeatures m = AdvicePromptBuilderTest.market();
    // 20거래일 창: 09-14 진입 → 10-13 청산 (featuresFor(base, 20) 이 계산한 값이라고 가정)
    when(marketFeatures.features(base, 20)).thenReturn(new MarketFeatures(m.asOf(), m.indices(), m.flows(), m.global(), m.topSectors(),
        m.bottomSectors(), m.sigma5d(), m.globalAsOf(), m.globalAgeTradingDays(), m.flowAsOf(), m.sectorAsOf(), LocalDate.of(2026, 9, 14),
        LocalDate.of(2026, 10, 13), m.trends(), m.links(), m.sectorIndexAsOf(), regime));
    kr.hvy.blog.modules.advisor.domain.model.ScreeningResult s8 = AdvicePromptBuilderTest.screening(8);
    when(screening.screen(eq(base), eq(AdviceKind.H20), any())).thenReturn(Optional.of(
        new kr.hvy.blog.modules.advisor.domain.model.ScreeningResult(s8.baseDate(), s8.universeSize(), s8.cutSize(), 2L, s8.candidates())));
    when(weightSets.find(2L)).thenReturn(Optional.of(h20Set));
  }

  private AdvisorExecution h20Execution() {
    AdvisorRun run = AdvisorRun.builder().runId(1400L).jobType(AdvisorJobType.ADVISE_H20).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }

  @Test
  @DisplayName("M7: H20 활성 가중치 세트가 없으면 DAILY 세트로 폴백하지 않고 SKIPPED — 사유가 skip.WEIGHTS 메타에 남고 LLM·스크리닝·저장 0")
  void h20SkipsWithoutWeightSet() {
    when(screening.weightSetFor(AdviceKind.H20)).thenReturn(Optional.empty());

    AdvisorExecution execution = h20Execution();
    new H20AdviseJob(job).execute(execution);

    assertThat(execution.isSkipped()).isTrue();
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SKIPPED);
    assertThat((String) execution.metadata("skip.WEIGHTS")).contains("H20").contains("h=20").contains("IC_BACKFILL?horizon=20");
    assertThat(execution.skipReason()).isEqualTo(execution.metadata("skip.WEIGHTS"));
    assertThat(llmCalls.get()).isZero();
    verify(screening, never()).screen(any());
    verify(screening, never()).screen(any(), any(AdviceKind.class), any());
    verify(adviceWriter, never()).insertHeader(any());
  }

  @Test
  @DisplayName("M7: H20 은 horizon_days=20·kind=H20 으로 LIVE 와 QUANT_TOPN 섀도를 저장하고, 뉴스·recentOutcomes·교훈·실적 블록 없이 advice-h20-v2 로 1회 판단해 '20일 관점 추천' 으로 발행한다")
  void h20SavesTwentyDayAdviceWithoutNewsOrMemory() {
    properties.getNews().setEnabled(true);   // 뉴스가 켜져 있어도 H20 입력에는 싣지 않는다
    when(adviceWriter.countLivePicks(AdviceKind.DAILY)).thenReturn(10_000);   // 300 게이트를 넘겨도 메모리를 싣지 않는다
    AdviseJob.ScoreHook hook = mock(AdviseJob.ScoreHook.class);
    when(hookProvider.getIfAvailable()).thenReturn(hook);
    stubH20(null);

    AdvisorExecution execution = h20Execution();
    new H20AdviseJob(job).execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).as("LIVE 1회 — LLM 섀도 없음").isEqualTo(1);
    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(headers.capture());
    assertThat(headers.getAllValues()).extracting(AdviceHeader::variant).containsExactly(AdviceVariant.QUANT_TOPN, AdviceVariant.LIVE);
    assertThat(headers.getAllValues()).allSatisfy(h -> {
      assertThat(h.adviceKind()).isEqualTo(AdviceKind.H20);
      assertThat(h.horizonDays()).isEqualTo(20);
      assertThat(h.weightSetId()).as("H20 가중치 세트").isEqualTo(2L);
      assertThat(h.exitDate()).as("20거래일 창").isEqualTo(LocalDate.of(2026, 10, 13));
    });
    AdviceHeader live = headers.getAllValues().get(1);
    assertThat(live.promptVersion()).isEqualTo(PromptResources.H20_VERSION).isEqualTo("advice-h20-v2");
    assertThat(live.memoryJson()).isNull();
    assertThat(live.activeLessonIds()).isEmpty();
    verify(adviceWriter).find(base, AdviceKind.H20, AdviceVariant.LIVE);
    verify(adviceWriter).find(base, AdviceKind.H20, AdviceVariant.QUANT_TOPN);

    // 뉴스·메모리 조회 자체가 없다 (뉴스·빠른 층·느린 층·교훈)
    verify(newsFeatures, never()).news(any(), any());
    verify(recentOutcomes, never()).block(any());
    verify(adviceWriter, never()).countLivePicks(any());
    verify(lessons, never()).findByStatus(any());
    verify(hook, never()).scoreDue(any());
    verify(icService, never()).computeIncremental(any());
    ArgumentCaptor<kr.hvy.blog.modules.advisor.domain.model.PromptInputRow> inputs = ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.domain.model.PromptInputRow.class);
    verify(promptInputs).upsert(inputs.capture());
    assertThat(inputs.getValue().promptVersion()).isEqualTo("advice-h20-v2");
    assertThat(inputs.getValue().userPayload()).contains("\"horizonDays\":20").contains("20번째 영업일 종가")
        .doesNotContain("\"news\"").doesNotContain("recentOutcomes").doesNotContain("\"scoreboard\"").doesNotContain("\"lessons\"");
    assertThat(execution.metadata("skip.NEWS")).isEqualTo(AdviseJob.H20_SKIP);
    assertThat(execution.metadata("skip.NOTES")).isEqualTo(AdviseJob.H20_SKIP);
    assertThat(execution.metadata("skip.SHADOW_QUANT_BROAD")).isEqualTo(AdviseJob.H20_SKIP);
    assertThat(execution.metadata("skip.SHADOW_NOMEM")).isEqualTo(AdviseJob.H20_SKIP);
    assertThat(execution.metadata("horizonDays")).isEqualTo(20);

    ArgumentCaptor<SlackMessage> message = ArgumentCaptor.forClass(SlackMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().getFallbackText()).contains("20일 관점 추천");
    assertThat(message.getValue().toBlocks().toString()).contains("20일 관점 추천 (20거래일)").contains("주간 20거래일 판단입니다");
  }

  @Test
  @DisplayName("M7: 금요일이 휴장이면(게이트 tradingDay=false) H20 은 LLM 없이 SKIPPED — 마감 경보도 없다")
  void h20SkipsOnHoliday() {
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(false, false, false, false, DataQuality.OK, "휴장일 " + base));
    stubH20(null);

    AdvisorExecution execution = h20Execution();
    new H20AdviseJob(job).execute(execution);

    assertThat(execution.isSkipped()).isTrue();
    assertThat(execution.skipReason()).contains("휴장일");
    assertThat(llmCalls.get()).isZero();
    verify(adviceWriter, never()).insertHeader(any());
    verify(notifier, never()).alert(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  @DisplayName("M7: 같은 기준일 H20 LIVE 가 이미 있으면 SKIPPED — 같은 날 DAILY 가 있는 것(alreadyDone)은 막지 않는다")
  void h20SkipsWhenAlreadyExists() {
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, true, true, false, DataQuality.OK, "이미 판단이 있습니다"));
    stubH20(null);
    when(adviceWriter.find(base, AdviceKind.H20, AdviceVariant.LIVE)).thenReturn(Optional.of(AdviceHeader.builder().adviceId(91L).build()));

    AdvisorExecution execution = h20Execution();
    new H20AdviseJob(job).execute(execution);
    assertThat(execution.isSkipped()).isTrue();
    assertThat(execution.skipReason()).contains("advice=91");
    assertThat(llmCalls.get()).isZero();
  }

  @Test
  @DisplayName("M7: 정책 표는 H20 픽 범위(3~8)로 다시 계산돼 가드가 강제한다 — BEAR 면 LONG ≤ max(3, 8−2)=6·확신 ≤ 0.70, regime_json·프롬프트도 같은 한도")
  void h20PolicyRecomputedForOwnBounds() {
    MarketRegime daily = new MarketRegime("0001", LocalDate.of(2026, 9, 11), MarketTrendCode.BEAR, -3, VolRegimeCode.NORMAL, 0.5, 0.01, 1200,
        new RegimePolicy(properties).limits(MarketTrendCode.BEAR, VolRegimeCode.NORMAL), List.of());
    assertThat(daily.policy().longMax()).as("DAILY 범위(3~10) 한도").isEqualTo(8);
    stubH20(daily);

    AdvisorExecution execution = h20Execution();
    new H20AdviseJob(job).execute(execution);

    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(headers.capture());
    AdviceHeader live = headers.getAllValues().get(1);
    assertThat(live.regime().policy().longMax()).isEqualTo(6);
    assertThat(live.regime().policy().convictionCap()).isEqualTo(0.70);
    @SuppressWarnings("unchecked")
    Map<String, Object> applied = (Map<String, Object>) live.guard().get("policy");
    assertThat(applied).containsEntry("longMax", 6).containsEntry("convictionCap", 0.70).containsEntry("cappedConviction", 1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertPicks(anyLong(), picks.capture());
    assertThat(picks.getAllValues().get(1)).filteredOn(p -> p.ticker().equals("T00")).extracting(PickRow::conviction).containsExactly(0.70);
    ArgumentCaptor<kr.hvy.blog.modules.advisor.domain.model.PromptInputRow> inputs = ArgumentCaptor.forClass(kr.hvy.blog.modules.advisor.domain.model.PromptInputRow.class);
    verify(promptInputs).upsert(inputs.capture());
    assertThat(inputs.getValue().userPayload()).contains("\"longMax\":6");
  }

  @Test
  @DisplayName("M7: H20 가드는 H20 픽 상한으로 자른다 — pick-max 2 면 확신 상위 2개만 남는다(DAILY pick-max 와 무관)")
  void h20GuardUsesOwnPickMax() {
    properties.getH20().setPickMin(1);
    properties.getH20().setPickMax(2);
    stubH20(null);

    AdvisorExecution execution = h20Execution();
    new H20AdviseJob(job).execute(execution);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertPicks(anyLong(), picks.capture());
    assertThat(picks.getAllValues().get(1)).extracting(PickRow::ticker).containsExactly("T00", "T01");
    ArgumentCaptor<AdviceHeader> headers = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertHeader(headers.capture());
    assertThat(headers.getAllValues().get(1).guard()).containsEntry("truncated", 1);
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(1284L).jobType(AdvisorJobType.ADVISE).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }
}
