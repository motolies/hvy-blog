package kr.hvy.blog.modules.stock.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.common.observability.TraceBoundary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockCollectStartupListenerTest {

  private final CollectRunService runService = mock(CollectRunService.class);
  private final KisProperties properties = new KisProperties();
  /** 리스너 본문을 Observation 경계로 감싸는 것 외에 동작은 바뀌지 않으므로 실제 레지스트리로 충분하다. */
  private final TraceBoundary traceBoundary = new TraceBoundary(ObservationRegistry.create());
  private final StockCollectStartupListener listener =
      new StockCollectStartupListener(runService, properties, traceBoundary);

  @Test
  @DisplayName("단일 인스턴스(기본값)는 기동 시 RUNNING run 을 나이와 무관하게 전부 정리한다")
  void reconcilesAllByDefault() {
    listener.reconcileStaleRuns();
    verify(runService).reconcileStaleRuns(Duration.ZERO);
  }

  @Test
  @DisplayName("다중 인스턴스 설정이면 stale-after 를 넘긴 run 만 정리하고, 정리 실패는 기동을 막지 않는다")
  void reconcilesStaleOnlyWhenConfigured() {
    properties.getRun().setReconcileAllOnStartup(false);
    properties.getRun().setStaleAfter(Duration.ofHours(2));
    listener.reconcileStaleRuns();
    verify(runService).reconcileStaleRuns(Duration.ofHours(2));

    when(runService.reconcileStaleRuns(any())).thenThrow(new IllegalStateException("db down"));
    listener.reconcileStaleRuns(); // 예외가 전파되지 않는다
  }
}
