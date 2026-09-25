package kr.hvy.blog.modules.advisor.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.application.service.AdvisorAlreadyRunningException;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.StockLookupReader;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * 채팅 수시 판단 요청의 판정 순서 — 허용 사용자 → 기준일 → 같은 기준일 존재 → 일 상한 → CHAT 트리거.
 */
class AdhocAdviceRequesterTest {

  private final AdvisorChatProperties properties = new AdvisorChatProperties(new MockEnvironment(), new AdvisorProperties(new MockEnvironment()));
  private final ChatBudgetGuard budget = mock(ChatBudgetGuard.class);
  private final AdvisorOrchestrator orchestrator = mock(AdvisorOrchestrator.class);
  private final AdvisorGateService gate = mock(AdvisorGateService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final StockLookupReader reader = mock(StockLookupReader.class);
  private final LocalDate today = MarketClock.today();
  private AdhocAdviceRequester requester;

  static AdvisorGateService.Decision ready() {
    return new AdvisorGateService.Decision(true, false, true, false, DataQuality.OK, "DAILY 완료");
  }

  @BeforeEach
  void setUp() {
    properties.setAllowedUserIds(List.of("U1"));
    requester = new AdhocAdviceRequester(properties, budget, orchestrator, gate, adviceWriter, reader);
    when(reader.latestMetricDate(today)).thenReturn(Optional.of(today));
    when(gate.decide(today)).thenReturn(ready());
    when(adviceWriter.find(any(), any(), any())).thenReturn(Optional.empty());
    when(budget.checkAdhocQuota()).thenReturn(Optional.empty());
    when(orchestrator.trigger(any(), any(), any(), anyMap())).thenReturn(
        new AdvisorOrchestrator.TriggerResult(AdvisorRun.builder().runId(55L).jobType(AdvisorJobType.ADVISE_ADHOC).build(), true));
  }

  @Test
  @DisplayName("허용 목록 밖 사용자·사용자 없음은 FORBIDDEN 이고 상한·트리거를 건드리지 않는다")
  void forbidden() {
    assertThat(requester.request("U9").status()).isEqualTo(AdhocAdviceRequester.Status.FORBIDDEN);
    assertThat(requester.request(null).status()).isEqualTo(AdhocAdviceRequester.Status.FORBIDDEN);
    verify(budget, never()).checkAdhocQuota();
    verify(orchestrator, never()).trigger(any(), any(), any(), anyMap());
  }

  @Test
  @DisplayName("일 상한 초과는 LIMIT 이고 트리거하지 않는다")
  void limit() {
    when(budget.checkAdhocQuota()).thenReturn(Optional.of(new ChatBudgetGuard.Refusal("ADHOC_LIMIT", "한도 3회")));
    AdhocAdviceRequester.Outcome o = requester.request("U1");
    assertThat(o.status()).isEqualTo(AdhocAdviceRequester.Status.LIMIT);
    assertThat(o.message()).isEqualTo("한도 3회");
    verify(orchestrator, never()).trigger(any(), any(), any(), anyMap());
  }

  @Test
  @DisplayName("통과하면 ADVISE_ADHOC 를 CHAT 트리거·요청자 메타로 비동기 실행하고 run id 를 돌려준다")
  void started() {
    AdhocAdviceRequester.Outcome o = requester.request("U1");
    assertThat(o.status()).isEqualTo(AdhocAdviceRequester.Status.STARTED);
    assertThat(o.runId()).isEqualTo(55L);
    assertThat(o.baseDate()).isEqualTo(today);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator).trigger(eq(AdvisorJobType.ADVISE_ADHOC), eq(today), eq(AdvisorTriggerType.CHAT), meta.capture());
    assertThat(meta.getValue()).containsEntry("requestedBy", "U1");
  }

  @Test
  @DisplayName("같은 기준일 ADHOC 가 있으면 한도와 무관하게 EXISTS 로 그 id 를 돌려준다(새로 만들지 않는다)")
  void existsBeforeLimit() {
    when(budget.checkAdhocQuota()).thenReturn(Optional.of(new ChatBudgetGuard.Refusal("ADHOC_LIMIT", "한도")));
    when(adviceWriter.find(today, AdviceKind.ADHOC, AdviceVariant.LIVE)).thenReturn(Optional.of(AdviceHeader.builder().adviceId(9L).runId(3L).build()));
    AdhocAdviceRequester.Outcome o = requester.request("U1");
    assertThat(o.status()).isEqualTo(AdhocAdviceRequester.Status.EXISTS);
    assertThat(o.adviceId()).isEqualTo(9L);
    verify(orchestrator, never()).trigger(any(), any(), any(), anyMap());
  }

  @Test
  @DisplayName("오늘 지표는 있지만 DAILY 수집이 덜 끝났으면 직전 거래일로 내려 판단한다")
  void fallsBackToPreviousTradingDay() {
    LocalDate previous = today.minusDays(1);
    when(gate.decide(today)).thenReturn(new AdvisorGateService.Decision(true, false, false, false, DataQuality.OK, "DAILY 수집 중"));
    when(reader.latestMetricDate(previous)).thenReturn(Optional.of(previous));
    when(gate.decide(previous)).thenReturn(ready());
    assertThat(requester.request("U1").baseDate()).isEqualTo(previous);
    verify(orchestrator).trigger(eq(AdvisorJobType.ADVISE_ADHOC), eq(previous), eq(AdvisorTriggerType.CHAT), anyMap());
  }

  @Test
  @DisplayName("이미 수시 판단이 돌고 있으면 RUNNING 과 그 run id")
  void alreadyRunning() {
    when(orchestrator.trigger(any(), any(), any(), anyMap())).thenThrow(new AdvisorAlreadyRunningException(AdvisorJobType.ADVISE_ADHOC, 41L));
    AdhocAdviceRequester.Outcome o = requester.request("U1");
    assertThat(o.status()).isEqualTo(AdhocAdviceRequester.Status.RUNNING);
    assertThat(o.runId()).isEqualTo(41L);
  }
}
