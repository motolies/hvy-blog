package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.LongTermFactorRow;
import kr.hvy.blog.modules.advisor.domain.model.MarketFeatures;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.domain.model.PromptInputRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.PromptInputWriter;
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
import org.springframework.mock.env.MockEnvironment;

/**
 * 장기 규칙 추천 파이프라인(M8): 규칙 결과 정본(LLM 이 종목을 바꿔도 유지)·fail-open·kind/horizon_days/regime_json 저장·게이트·판정 불가 라벨.
 */
class LongTermAdviseJobTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final AdvisorGateService gate = mock(AdvisorGateService.class);
  private final MarketFeatureService marketFeatures = mock(MarketFeatureService.class);
  private final LongTermScreeningService screening = mock(LongTermScreeningService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final PromptInputWriter promptInputs = mock(PromptInputWriter.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);
  private final LocalDate base = LocalDate.of(2026, 10, 2);
  private final AtomicInteger llmCalls = new AtomicInteger();
  /** 규칙 픽은 T09,T08,T07 — LLM 은 순서를 뒤집고 규칙 밖 T00 을 넣고 T08 을 뺐다 */
  static final String REPLY = """
      {"picks":[{"ticker":"T07","thesis":"t7 근거","risk":"t7 위험"},{"ticker":"T00","thesis":"끼워 넣기","risk":"x"},
                {"ticker":"T09","thesis":"t9 근거","risk":"t9 위험"}],
       "summary":"모멘텀 주도"}
      """;
  private RuntimeException llmFailure;
  private LongTermAdviseJob job;

  @BeforeEach
  void setUp() {
    properties.getLongTerm().setPickCount(3);
    properties.getLongTerm().setCandidateLimit(5);
    ChatModel stub = prompt -> {
      llmCalls.incrementAndGet();
      if (llmFailure != null) {
        throw llmFailure;
      }
      return new ChatResponse(List.of(new Generation(new AssistantMessage(REPLY))),
          ChatResponseMetadata.builder().id("resp").model("judge-x").usage(new DefaultUsage(3000, 800)).build());
    };
    job = new LongTermAdviseJob(properties, gate, marketFeatures, screening, new PromptResources(),
        new MarketJudgeClient(ChatClient.create(stub), "judge-x"), adviceWriter, promptInputs, notifier);
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, true, true, false, DataQuality.OK, "이미 판단이 있습니다"));
    MarketFeatures m = AdvicePromptBuilderTest.market();
    MarketRegime regime = new MarketRegime("0001", base, MarketTrendCode.BEAR, -3, VolRegimeCode.HIGH, 0.9, 0.02, 1200,
        new RegimePolicy(properties).limits(MarketTrendCode.BEAR, VolRegimeCode.HIGH), List.of());
    when(marketFeatures.features(base, 60)).thenReturn(new MarketFeatures(m.asOf(), m.indices(), m.flows(), m.global(), m.topSectors(), m.bottomSectors(),
        m.sigma5d(), m.globalAsOf(), m.globalAgeTradingDays(), m.flowAsOf(), m.sectorAsOf(), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 29),
        m.trends(), m.links(), m.sectorIndexAsOf(), regime));
    when(marketFeatures.features(base, 180)).thenReturn(m);
    List<LongTermFactorRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(LongTermScorerTest.row(i, true, "S" + i));
    }
    when(screening.rank(base)).thenReturn(LongTermScorer.rank(rows, new LongTermScorer.Rule(properties.getLongTerm().getWeights(), false,
        properties.getLongTerm().getMinCoverage(), 5, 3, 3)));
    when(adviceWriter.find(any(), any(), any())).thenReturn(Optional.empty());
    when(adviceWriter.insertHeader(any())).thenReturn(900L);
    when(notifier.publish(any())).thenReturn(true);
  }

  private AdvisorExecution execution(AdvisorJobType type) {
    AdvisorRun run = AdvisorRun.builder().runId(1500L).jobType(type).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(base).build();
    return new AdvisorExecution(run, base, properties);
  }

  @Test
  @DisplayName("가드: LLM 이 순서를 바꾸고 규칙 밖 종목을 넣고 하나를 빼도 저장 픽은 규칙 상위 N(T09,T08,T07) 그대로 — kind=H60·horizon_days=60·regime_json(policy 없음)")
  void ruleResultPersistsDespiteLlm() {
    AdvisorExecution execution = execution(AdvisorJobType.ADVISE_H60);
    new H60AdviseJob(job).execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.SUCCESS);
    assertThat(llmCalls.get()).isEqualTo(1);
    ArgumentCaptor<AdviceHeader> header = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter).insertHeader(header.capture());
    AdviceHeader h = header.getValue();
    assertThat(h.adviceKind()).isEqualTo(AdviceKind.H60);
    assertThat(h.variant()).isEqualTo(AdviceVariant.LIVE);
    assertThat(h.horizonDays()).isEqualTo(60);
    assertThat(h.exitDate()).isEqualTo(LocalDate.of(2026, 12, 29));
    assertThat(h.promptVersion()).isEqualTo(PromptResources.LONGTERM_VERSION);
    assertThat(h.weightSetId()).as("사전 고정 가중치 — 학습 세트 없음").isNull();
    assertThat(h.regime().trend()).isEqualTo(MarketTrendCode.BEAR);
    assertThat(h.regime().policy()).as("정책 표는 규칙 선택에 적용하지 않는다").isNull();
    assertThat(h.guard()).containsEntry("ruleOverride", true).containsEntry("unknownTicker", 1).containsEntry("missing", 1)
        .containsEntry("verdict", AdvisorKpiService.UNJUDGEABLE_LABEL).containsKey("weights");
    assertThat(h.summary()).isEqualTo("모멘텀 주도");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter).insertPicks(eq(900L), picks.capture());
    assertThat(picks.getValue()).extracting(PickRow::ticker).containsExactly("T09", "T08", "T07");
    assertThat(picks.getValue()).extracting(PickRow::thesis).containsExactly("t9 근거", LongTermNarrativeGuard.NO_NARRATIVE, "t7 근거");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<kr.hvy.blog.modules.advisor.domain.model.CandidateRow>> candidates = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter).insertCandidates(eq(900L), candidates.capture());
    assertThat(candidates.getValue()).as("후보 = 규칙 상위 candidate-limit(픽−후보군 모집단)").hasSize(5);

    ArgumentCaptor<PromptInputRow> input = ArgumentCaptor.forClass(PromptInputRow.class);
    verify(promptInputs).upsert(input.capture());
    assertThat(input.getValue().userPayload()).contains("\"horizonDays\":60").contains("\"verdict\":\"판정 불가: 표본 부족, 2년 이상 필요\"")
        .contains("v_MOM_12_1").contains("\"rs120\"").doesNotContain("\"policy\"");

    ArgumentCaptor<SlackMessage> message = ArgumentCaptor.forClass(SlackMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().getFallbackText()).contains("60일 관점 규칙 추천").contains(AdvisorKpiService.UNJUDGEABLE_LABEL);
    assertThat(message.getValue().toBlocks().toString()).contains("판정 불가: 표본 부족, 2년 이상 필요").contains(LongTermNarrativeGuard.NO_NARRATIVE);
    verify(adviceWriter).markPublished(eq(900L), any());
  }

  @Test
  @DisplayName("fail-open: 서술 LLM 이 실패해도 규칙 픽을 '서술 없음' 으로 저장·발행하고 run 은 PARTIAL, model=rule-only·narrative=FAILED 메타")
  void llmFailureFailsOpen() {
    llmFailure = new IllegalStateException("upstream 503");
    AdvisorExecution execution = execution(AdvisorJobType.ADVISE_H180);
    new H180AdviseJob(job).execute(execution);

    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    assertThat(execution.metadata("narrative")).isEqualTo("FAILED");
    ArgumentCaptor<AdviceHeader> header = ArgumentCaptor.forClass(AdviceHeader.class);
    verify(adviceWriter).insertHeader(header.capture());
    assertThat(header.getValue().adviceKind()).isEqualTo(AdviceKind.H180);
    assertThat(header.getValue().horizonDays()).isEqualTo(180);
    assertThat(header.getValue().model()).isEqualTo(LongTermAdviseJob.RULE_ONLY_MODEL);
    assertThat(header.getValue().guard()).containsEntry("narrative", "FAILED");
    assertThat((String) header.getValue().guard().get("failure")).contains("503");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PickRow>> picks = ArgumentCaptor.forClass(List.class);
    verify(adviceWriter).insertPicks(anyLong(), picks.capture());
    assertThat(picks.getValue()).extracting(PickRow::ticker).containsExactly("T09", "T08", "T07");
    assertThat(picks.getValue()).extracting(PickRow::thesis).containsOnly(LongTermNarrativeGuard.NO_NARRATIVE);
    verify(promptInputs, never()).upsert(any());
    ArgumentCaptor<SlackMessage> message = ArgumentCaptor.forClass(SlackMessage.class);
    verify(notifier).publish(message.capture());
    assertThat(message.getValue().toBlocks().toString()).contains("서술 생성 실패");
  }

  @Test
  @DisplayName("게이트: 휴장·입력 미준비·같은 기준일 같은 종류가 있으면 LLM·스크리닝 없이 SKIPPED")
  void gateSkips() {
    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(false, false, false, false, DataQuality.OK, "휴장일 " + base));
    AdvisorExecution holiday = execution(AdvisorJobType.ADVISE_H60);
    new H60AdviseJob(job).execute(holiday);
    assertThat(holiday.skipReason()).contains("휴장일");

    when(gate.decide(any(), any())).thenReturn(new AdvisorGateService.Decision(true, false, true, false, DataQuality.OK, "DAILY 완료"));
    when(adviceWriter.find(base, AdviceKind.H60, AdviceVariant.LIVE)).thenReturn(Optional.of(AdviceHeader.builder().adviceId(55L).build()));
    AdvisorExecution existing = execution(AdvisorJobType.ADVISE_H60);
    new H60AdviseJob(job).execute(existing);
    assertThat(existing.skipReason()).contains("advice=55");

    assertThat(llmCalls.get()).isZero();
    verify(screening, never()).rank(any());
    verify(adviceWriter, never()).insertHeader(any());
  }

  @Test
  @DisplayName("규칙 픽이 pick-min 미만이면 FAILED(미발행)")
  void tooFewPicksFails() {
    properties.getLongTerm().setPickMin(4);
    assertThatThrownBy(() -> new H60AdviseJob(job).execute(execution(AdvisorJobType.ADVISE_H60))).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("너무 적습니다");
    verify(notifier, never()).publish(any());
  }

  @Test
  @DisplayName("M5 인프라: H60·H180 은 자기 결정 호라이즌 하나(60·180)로만 채점되고 KPI 요약에 판정 불가 라벨이 붙는다(DAILY·H20 은 없음)")
  void scoredWithOwnWindowAndLabelled() {
    assertThat(properties.scoreHorizons(AdviceKind.H60)).containsExactly(60);
    assertThat(properties.scoreHorizons(AdviceKind.H180)).containsExactly(180);
    AdvisorKpiService kpi = new AdvisorKpiService(null, properties);
    assertThat(kpi.verdictLabel(AdviceKind.H60)).isEqualTo(AdvisorKpiService.UNJUDGEABLE_LABEL);
    assertThat(kpi.verdictLabel(AdviceKind.H180)).isEqualTo(AdvisorKpiService.UNJUDGEABLE_LABEL);
    assertThat(kpi.verdictLabel(AdviceKind.DAILY)).isNull();
    assertThat(kpi.verdictLabel(AdviceKind.H20)).isNull();
  }
}
