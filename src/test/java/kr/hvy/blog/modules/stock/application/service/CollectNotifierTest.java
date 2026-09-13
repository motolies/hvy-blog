package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 알림 임계 규칙 중 비율 밖의 층: flush 실패 floor(최소 NOTIFY)와 단계 결손(비율 무관 ERROR, 2026-09-13).
 * 우선순위 ERROR > NOTIFY > 로그, 한 run 에 한 통.
 */
class CollectNotifierTest {

  private final Notify notify = mock(Notify.class);
  private final CollectNotifier notifier = new CollectNotifier(Optional.of(notify));
  private final CollectRunService runService = mock(CollectRunService.class);
  private final StockCollectRun run = StockCollectRun.builder().runId(7L).jobType(CollectJobType.MASTER)
      .triggerType(TriggerType.API).build();

  /** 3000 종목 처리 + 실패 1건 (비율 0.03%, 임계 미만) */
  private CollectExecution oneFailureIn3000(String target) {
    CollectExecution execution = new CollectExecution(run, null, runService);
    for (int i = 0; i < 3000; i++) {
      execution.targetDone();
    }
    execution.recordFailure(target, "실패");
    return execution;
  }

  @Test
  @DisplayName("실패율이 1% 미만이라도 flush 실패가 있으면 NOTIFY 채널로 멘션 없이 보낸다")
  void flushFailureFloorsToNotify() {
    doThrow(new DataAccessResourceFailureException("db down"))
        .when(runService).addCounters(eq(7L), anyLong(), anyLong(), anyLong());
    CollectExecution execution = oneFailureIn3000("STEP:FLUSH");
    execution.addRows(1);
    execution.flush(); // flushFailures = 1

    notifier.afterRun(run, execution, CollectStatus.PARTIAL);

    ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
    verify(notify).sendMessage(captor.capture());
    assertThat(captor.getValue().getChannel()).isEqualTo(SlackChannel.NOTIFY.getChannel());
    assertThat(captor.getValue().isNotify()).isFalse();
    assertThat(captor.getValue().getMessage()).contains("flush 실패 1회");
  }

  @Test
  @DisplayName("flush 실패가 없으면 1% 미만 실패는 기존대로 알림을 생략한다")
  void belowThresholdWithoutFlushFailureStaysQuiet() {
    CollectExecution execution = oneFailureIn3000("005930");

    notifier.afterRun(run, execution, CollectStatus.PARTIAL);

    verify(notify, never()).sendMessage(any(NotifyRequest.class));
  }

  @Test
  @DisplayName("단계가 예외로 죽으면 run 전체 실패율이 0.03% 라도 #hvy-error + 멘션으로 단계명과 예외를 알린다")
  void stepExceptionAlertsErrorRegardlessOfRatio() {
    CollectExecution execution = oneFailureIn3000("STEP:ETF_NAV");
    execution.recordStep("ETF_NAV", "FAILED", 0, 1);

    notifier.afterRun(run, execution, CollectStatus.PARTIAL);

    NotifyRequest sent = captureSingle();
    assertThat(sent.getChannel()).isEqualTo(SlackChannel.ERROR.getChannel());
    assertThat(sent.isNotify()).isTrue();
    assertThat(sent.getMessage()).contains("단계 실패").contains("ETF_NAV[예외 실패]");
  }

  @Test
  @DisplayName("단계 안 실패율이 5% 이상이면(시장별 투자자 2개 중 1개) 단계 결손으로 보고 #hvy-error 로 알린다")
  void stepWithHighOwnFailureRatioIsBroken() {
    CollectExecution execution = oneFailureIn3000("KOSDAQ");
    execution.recordStep("MARKET_INVESTOR", "OK", 1, 1);

    notifier.afterRun(run, execution, CollectStatus.PARTIAL);

    NotifyRequest sent = captureSingle();
    assertThat(sent.getChannel()).isEqualTo(SlackChannel.ERROR.getChannel());
    assertThat(sent.getMessage()).contains("MARKET_INVESTOR[1/2 실패]");
  }

  @Test
  @DisplayName("단계 안 실패율이 낮으면(2,999 성공 + 1 실패) 단계 규칙이 기존 무음 구간에 소음을 더하지 않는다")
  void stepWithLowOwnRatioStaysQuiet() {
    CollectExecution execution = oneFailureIn3000("005930");
    execution.recordStep("PRICE", "OK", 2999, 1);

    notifier.afterRun(run, execution, CollectStatus.PARTIAL);

    verify(notify, never()).sendMessage(any(NotifyRequest.class));
  }

  @Test
  @DisplayName("flush 실패와 단계 결손이 겹치면 NOTIFY 가 아니라 ERROR 한 통만 보낸다")
  void brokenStepOutranksFlushFloor() {
    doThrow(new DataAccessResourceFailureException("db down"))
        .when(runService).addCounters(eq(7L), anyLong(), anyLong(), anyLong());
    CollectExecution execution = oneFailureIn3000("STEP:DERIVED");
    execution.recordStep("DERIVED", "FAILED", 0, 1);
    execution.addRows(1);
    execution.flush();

    notifier.afterRun(run, execution, CollectStatus.PARTIAL);

    NotifyRequest sent = captureSingle();
    assertThat(sent.getChannel()).isEqualTo(SlackChannel.ERROR.getChannel());
    assertThat(sent.getMessage()).contains("flush 실패 1회").contains("DERIVED");
  }

  private NotifyRequest captureSingle() {
    ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
    verify(notify, times(1)).sendMessage(captor.capture());
    return captor.getValue();
  }
}
