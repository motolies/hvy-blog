package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * 알림 임계 규칙 중 flush 실패 floor: 종목 실패율이 1% 미만이어도 카운터 flush 가 실패했으면 최소 NOTIFY 로 올린다.
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
}
