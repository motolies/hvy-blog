package kr.hvy.blog.modules.stock.application.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 수집 결과 Slack 알림. 잡이 종목 단위로 실패를 삼키므로 전역 예외 핸들러가 울리지 않는다 — 여기서 임계로 판단한다.
 * <ul>
 *   <li>실패율 &lt; 1%: run 에 PARTIAL 만 기록</li>
 *   <li>1% ~ 5%: #hvy-notify</li>
 *   <li>≥ 5% 또는 잡 자체 실패: #hvy-error + 멘션</li>
 *   <li>파이프라인 단계 결손(단계 예외 또는 단계 안 실패율 ≥ 5%): 종목 비율과 무관하게 #hvy-error + 멘션 (2026-09-13)</li>
 *   <li>run 카운터 flush 실패: 종목 비율과 무관한 인프라 신호라 비율 미만이어도 최소 #hvy-notify</li>
 *   <li>스케줄 트리거 거부(run 없음): #hvy-error + 멘션 (2026-09-13)</li>
 * </ul>
 * 메시지에는 종목을 나열하지 않고 결손 단계·대표 오류 3건·EGW00201 횟수만 넣는다.
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
   * 우선순위는 ERROR(종목 비율 ≥5% 또는 단계 결손) > NOTIFY(≥1% 또는 flush 실패) > 로그이고 한 run 에 한 통만 보낸다.
   * 실패가 0이면(휴장일 skip 포함) 알리지 않는다.
   */
  public void afterRun(StockCollectRun run, CollectExecution execution, CollectStatus status) {
    int failures = execution.failureCount();
    if (failures == 0) {
      return;
    }
    int total = Math.max(execution.processedCount() + failures, 1);
    double ratio = (double) failures / total;
    List<CollectExecution.StepResult> broken = brokenSteps(execution);
    String text = summary(run, execution, status, failures, total, broken);
    if (ratio >= ERROR_RATIO || !broken.isEmpty()) {
      send(SlackChannel.ERROR, text, true);
    } else if (ratio >= NOTIFY_RATIO || execution.flushFailures() > 0) {
      send(SlackChannel.NOTIFY, text, false);
    } else {
      log.info("수집 부분 실패(임계 미만, 알림 생략): {}", text.replace('\n', ' '));
    }
  }

  /**
   * 단계가 예외로 죽었거나(FAILED) 단계 안 실패율이 ERROR_RATIO 이상이면 "한 데이터 범주가 통째로 빠진 것" 으로 본다.
   * DAILY 는 종목 ≈2,700 을 처리하므로 ETF NAV 1,000종목 전부·시장별 투자자 2개 중 1개·MV 4개 중 1개가 죽어도 run 전체 비율로는
   * 0.04% 라 영원히 무음이었다(2026-09-13 스케줄러 활성화 전 점검). 단계 안 1~5% 는 종목 비율 규칙에 맡긴다(INDEX 코드 1개 상시 실패 같은 소음 방지).
   */
  static List<CollectExecution.StepResult> brokenSteps(CollectExecution execution) {
    return execution.steps().stream()
        .filter(step -> step.failed() || step.failureRatio() >= ERROR_RATIO)
        .toList();
  }

  /**
   * 잡 자체가 예외로 죽었을 때.
   */
  public void afterFailure(StockCollectRun run, CollectExecution execution, Exception cause) {
    String text = String.format("[주식 수집 실패] run=%d %s(%s)%n원인: %s%n처리 %d건, 행 %d, EGW00201 %d회%s",
        run.getRunId(), run.getJobType(), run.getJobType().getDesc(),
        cause.toString(), execution.processedCount(), execution.totalRows(), execution.rateLimitHits(),
        flushNote(execution));
    send(SlackChannel.ERROR, text, true);
  }

  /**
   * 스케줄 트리거가 run 을 만들기 전에 거부됐을 때(잡 미등록·KIS 키 누락·이미 실행 중·DB 오류).
   * run 이 없어 이력에도 남지 않고 AbstractScheduler 는 예외를 로그로만 삼키므로, 여기서 알리지 않으면 스케줄 잡이 며칠째 안 돌아도
   * 아무도 모른다(2026-09-13 스케줄러 활성화 전 점검). 빈도 상한이 cron 횟수라 소음이 될 수 없어 항상 #hvy-error + 멘션.
   */
  public void afterTriggerRejected(CollectJobType jobType, Exception cause) {
    String text = String.format("[주식 수집 미실행] SCHEDULER %s(%s)%n원인: %s%nrun 이 생성되지 않았습니다 — 원인 해소 후 POST /api/stock/admin/collect/%s 로 보충",
        jobType, jobType.getDesc(), cause, jobType.getCode());
    send(SlackChannel.ERROR, text, true);
  }

  private String summary(StockCollectRun run, CollectExecution execution, CollectStatus status, int failures, int total,
      List<CollectExecution.StepResult> broken) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("[주식 수집 %s] run=%d %s(%s)%n실패 %d / %d (%.1f%%), 행 %d, EGW00201 %d회%s",
        status, run.getRunId(), run.getJobType(), run.getJobType().getDesc(),
        failures, total, failures * 100.0 / total, execution.totalRows(), execution.rateLimitHits(),
        flushNote(execution)));
    if (!broken.isEmpty()) {
      sb.append("\n단계 실패: ")
          .append(broken.stream().map(step -> stepNote(step, execution)).collect(Collectors.joining(", ")));
    }
    List<CollectExecution.CollectFailure> sample = execution.failures();
    for (CollectExecution.CollectFailure failure : sample.subList(0, Math.min(3, sample.size()))) {
      sb.append("\n- ").append(failure.target()).append(": ").append(failure.message());
    }
    return sb.toString();
  }

  /**
   * 결손 단계 1개의 토막. 예외로 죽은 단계는 STEP:이름 으로 기록된 예외 메시지를, 비율 단계는 n/m 을 붙인다.
   */
  private static String stepNote(CollectExecution.StepResult step, CollectExecution execution) {
    if (step.failed()) {
      String target = "STEP:" + step.name();
      String cause = execution.failures().stream()
          .filter(failure -> target.equals(failure.target()))
          .map(CollectExecution.CollectFailure::message)
          .findFirst()
          .orElse("");
      return step.name() + "[예외 " + StringUtils.abbreviate(cause, 120) + "]";
    }
    return step.name() + "[" + step.failures() + "/" + (step.processed() + step.failures()) + " 실패]";
  }

  /**
   * 카운터 flush 실패가 있었으면 메시지에 붙일 토막.
   */
  private static String flushNote(CollectExecution execution) {
    int flushFailures = execution.flushFailures();
    return flushFailures > 0 ? ", flush 실패 " + flushFailures + "회" : "";
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
