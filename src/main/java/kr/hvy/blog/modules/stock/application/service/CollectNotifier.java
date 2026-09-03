package kr.hvy.blog.modules.stock.application.service;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 수집 결과 Slack 알림. 잡이 종목 단위로 실패를 삼키므로 전역 예외 핸들러가 울리지 않는다 — 여기서 임계로 판단한다.
 * <ul>
 *   <li>실패율 &lt; 1%: run 에 PARTIAL 만 기록</li>
 *   <li>1% ~ 5%: #hvy-notify</li>
 *   <li>≥ 5% 또는 잡 자체 실패: #hvy-error + 멘션</li>
 * </ul>
 * 메시지에는 종목을 나열하지 않고 대표 오류 3건과 EGW00201 횟수만 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectNotifier {

  static final double NOTIFY_RATIO = 0.01;
  static final double ERROR_RATIO = 0.05;

  private final Optional<Notify> notify;

  /**
   * 실패 건수로 종료 상태를 정한다.
   */
  public CollectStatus decideStatus(CollectExecution execution) {
    return execution.failureCount() > 0 ? CollectStatus.PARTIAL : CollectStatus.SUCCESS;
  }

  /**
   * 정상 종료(SUCCESS/PARTIAL) 후 임계에 따라 알린다.
   */
  public void afterRun(StockCollectRun run, CollectExecution execution, CollectStatus status) {
    int failures = execution.failureCount();
    if (failures == 0) {
      return;
    }
    int total = Math.max(execution.processedCount() + failures, 1);
    double ratio = (double) failures / total;
    String text = summary(run, execution, status, failures, total);
    if (ratio >= ERROR_RATIO) {
      send(SlackChannel.ERROR, text, true);
    } else if (ratio >= NOTIFY_RATIO) {
      send(SlackChannel.NOTIFY, text, false);
    } else {
      log.info("수집 부분 실패(임계 미만, 알림 생략): {}", text.replace('\n', ' '));
    }
  }

  /**
   * 잡 자체가 예외로 죽었을 때.
   */
  public void afterFailure(StockCollectRun run, CollectExecution execution, Exception cause) {
    String text = String.format("[주식 수집 실패] run=%d %s(%s)%n원인: %s%n처리 %d건, 행 %d, EGW00201 %d회",
        run.getRunId(), run.getJobType(), run.getJobType().getDesc(),
        cause.toString(), execution.processedCount(), execution.totalRows(), execution.rateLimitHits());
    send(SlackChannel.ERROR, text, true);
  }

  private String summary(StockCollectRun run, CollectExecution execution, CollectStatus status, int failures, int total) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("[주식 수집 %s] run=%d %s(%s)%n실패 %d / %d (%.1f%%), 행 %d, EGW00201 %d회",
        status, run.getRunId(), run.getJobType(), run.getJobType().getDesc(),
        failures, total, failures * 100.0 / total, execution.totalRows(), execution.rateLimitHits()));
    List<CollectExecution.CollectFailure> sample = execution.failures();
    for (CollectExecution.CollectFailure failure : sample.subList(0, Math.min(3, sample.size()))) {
      sb.append("\n- ").append(failure.target()).append(": ").append(failure.message());
    }
    return sb.toString();
  }

  /**
   * 잡 내부 검증 결과 등 임의 텍스트를 보낸다 (error=true 면 #hvy-error + 멘션).
   */
  public void notifyText(String text, boolean error) {
    send(error ? SlackChannel.ERROR : SlackChannel.NOTIFY, text, error);
  }

  private void send(SlackChannel channel, String text, boolean mention) {
    if (notify.isEmpty()) {
      log.warn("Slack Notify 빈 없음 — 알림 생략: {}", text.replace('\n', ' '));
      return;
    }
    try {
      notify.get().sendMessage(NotifyRequest.builder()
          .channel(channel.getChannel())
          .message(text)
          .isNotify(mention)
          .build());
    } catch (Exception e) {
      log.error("수집 결과 Slack 전송 실패(무시): {}", e.getMessage());
    }
  }
}
