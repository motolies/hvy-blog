package kr.hvy.blog.modules.stock.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 파생 MV 갱신(DERIVED_REFRESH 잡 + DAILY 마지막 단계). 순서가 의존성이다:
 * 수정계수 → 종목 일별 지표 → 지수 지표 → 섹터 일별. 없는 MV 는 건너뛰고 경고만 남긴다(psql 미적용 상태 허용).
 * 총 소요가 3분을 넘으면 증분 테이블 전환 신호로 보고 경고를 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedMetricRefreshService implements CollectJob {

  static final List<String> REFRESH_ORDER = List.of(
      DerivedViewRefresher.MV_ADJUST_FACTOR,
      "mv_stock_daily_metric",
      "mv_stock_index_metric",
      "mv_stock_sector_daily");
  static final long WARN_THRESHOLD_MS = 3 * 60_000L;

  private final DerivedViewRefresher viewRefresher;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.DERIVED_REFRESH;
  }

  @Override
  public void execute(CollectExecution execution) {
    refreshAll(execution);
  }

  /**
   * 존재하는 MV 를 순서대로 CONCURRENTLY 갱신하고 소요 시간을 메타데이터에 남긴다.
   *
   * @return 갱신한 MV 수
   */
  public int refreshAll(CollectExecution execution) {
    Map<String, Object> timings = new LinkedHashMap<>();
    long total = 0;
    int refreshed = 0;
    for (String name : REFRESH_ORDER) {
      if (execution.isCancelRequested()) {
        break;
      }
      if (!viewRefresher.materializedViewExists(name)) {
        timings.put(name, "MISSING");
        continue;
      }
      try {
        long ms = viewRefresher.refresh(name, properties.getDerived().isConcurrently(), properties.getDerived().getWorkMem(),
            properties.getDerived().getMaxParallelWorkers());
        timings.put(name, ms);
        total += ms;
        refreshed++;
        execution.targetDone();
      } catch (RuntimeException e) {
        timings.put(name, "FAILED: " + e.getMessage());
        execution.recordFailure(name, e.getMessage());
      }
    }
    execution.putMetadata("refreshMs", timings);
    execution.putMetadata("refreshTotalMs", total);
    if (total > WARN_THRESHOLD_MS) {
      log.warn("파생 MV 갱신이 3분을 넘었습니다({}ms). 증분 테이블 전환을 검토하세요", total);
      execution.putMetadata("warning", "MV 갱신 3분 초과");
    }
    return refreshed;
  }
}
