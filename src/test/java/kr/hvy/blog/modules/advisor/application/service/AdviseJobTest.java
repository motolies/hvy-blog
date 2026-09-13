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
  private final LocalDate base = LocalDate.of(2026, 9, 11);
  private final AtomicInteger llmCalls = new AtomicInteger();
  /** 후보 T00~T02 를 고르고 T00 은 입력 특징(r20=0.012345)을 허용오차 안에서 인용 */
  static final String REPLY = """
      {"regime":{"code":"RISK_ON","kospiDir":"UP","kosdaqDir":"NEUTRAL","pUp":"0.70","rationale":"근거"},
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
        new MarketJudgeClient(ChatClient.create(stub), "judge-x"), adviceWriter, promptInputs, lessons, notifier, hookProvider);
    when(hookProvider.getIfAvailable()).thenReturn(null);
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, false, true, false, DataQuality.OK, "DAILY 완료"));
    when(icService.computeIncremental()).thenReturn(Optional.empty());
    when(marketFeatures.features(base)).thenReturn(AdvicePromptBuilderTest.market());
    when(screening.screen(base)).thenReturn(AdvicePromptBuilderTest.screening(8));
    when(weightSets.find(1L)).thenReturn(Optional.of(WeightSet.builder().weightSetId(1L).source(WeightSetSource.SEED).active(true)
        .weights(List.of(SignalWeightRow.builder().signalCode("MOM_20D").baseWeight(0.12).multiplier(1).weight(0.12).enabled(true).build())).build()));
    when(adviceWriter.find(any(), anyString(), any())).thenReturn(Optional.empty());
    when(adviceWriter.findLatest(any(), any())).thenReturn(Optional.empty());
    when(adviceWriter.countLivePicks()).thenReturn(0);
    when(adviceWriter.insertHeader(any())).thenReturn(842L, 843L);
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
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter, org.mockito.Mockito.times(2)).insertPicks(anyLong(), picks.capture());
    assertThat(picks.getAllValues().get(0)).as("정량 섀도 top-N").hasSize(properties.getShadow().getQuantTopN());
    assertThat(picks.getAllValues().get(1)).extracting(PickRow::ticker).containsExactly("T00", "T01", "T02");
    verify(promptInputs).upsert(any());
    verify(notifier).publish(any(SlackMessage.class));
    verify(adviceWriter).markPublished(eq(843L), any());
    assertThat(execution.metadata("adviceId")).isEqualTo(843L);
    assertThat(execution.steps()).extracting(AdvisorExecution.StepResult::name)
        .contains("IC", "FEATURES", "SCREEN", "SHADOW_QUANT", "JUDGE", "SAVE", "PUBLISH", "SHADOW_NOMEM");
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
    when(icService.computeIncremental()).thenThrow(new IllegalStateException("ic boom"));
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.failures()).extracting(AdvisorExecution.Failure::target).contains("STEP:SCORE", "STEP:IC");
    verify(notifier).publish(any(SlackMessage.class));
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(1284L).jobType(AdvisorJobType.ADVISE).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }
}
