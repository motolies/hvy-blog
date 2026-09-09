package kr.hvy.blog.modules.stock.application.service;

import java.time.Duration;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.common.observability.TraceBoundary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 RUNNING run 을 정리한다. 프로세스가 죽으면 RUNNING 행이 남아 부분 유니크 인덱스가 다음 실행을 막는다.
 * 단일 인스턴스(kis.run.reconcile-all-on-startup=true)에서는 기동 시점에 살아 있는 실행이 없으므로 전부 FAILED 로
 * 확정하고, 다중 인스턴스에서는 stale-after 를 넘긴 행만 정리한다. 체크포인트는 그대로라 다음 트리거가 이어받는다.
 * 정리 실패는 기동을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCollectStartupListener {

  private final CollectRunService collectRunService;
  private final KisProperties properties;
  private final TraceBoundary traceBoundary;

  /**
   * ApplicationReadyEvent 에서 RUNNING run 을 정리한다.
   * <p>
   * 기동 스레드에는 HTTP·스케줄러 같은 프레임워크 경계가 없어 트레이스 스코프가 열려 있지 않다. 그래서
   * 여기서 남긴 경고 로그는 traceId 없이 나가고 /admin/system-log 에서 추적이 끊긴다.
   * {@link TraceBoundary} 로 감싸 이 리스너 안의 모든 로그(및 파생 스레드)가 하나의 traceId 를 갖게 한다.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void reconcileStaleRuns() {
    traceBoundary.run("startup.reconcileStaleRuns", this::doReconcileStaleRuns);
  }

  /**
   * 실제 정리 로직. 정리 실패는 기동을 막지 않으므로 예외를 삼킨다.
   */
  private void doReconcileStaleRuns() {
    try {
      boolean all = properties.getRun().isReconcileAllOnStartup();
      Duration olderThan = all ? Duration.ZERO : properties.getRun().getStaleAfter();
      int reconciled = collectRunService.reconcileStaleRuns(olderThan);
      if (reconciled > 0) {
        log.warn("기동 시 RUNNING 수집 run {}건을 FAILED 로 정리했습니다 (기준: {})", reconciled,
            all ? "전부(단일 인스턴스)" : olderThan + " 초과");
      }
    } catch (Exception e) {
      log.error("stale 수집 run 정리 실패(기동은 계속): {}", e.getMessage(), e);
    }
  }
}
