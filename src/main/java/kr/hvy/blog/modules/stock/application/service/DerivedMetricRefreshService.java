package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 파생 계층 갱신(DERIVED_REFRESH 잡 + DAILY 마지막 단계 + WEEKLY 전체 재계산). 순서가 의존성이다:
 * 수정계수 MV → 종목 일별 지표 테이블(증분 재계산) → 지수 지표 MV → 섹터 일별 MV.
 * 없는 객체는 건너뛰고 경고만 남긴다(psql 미적용 상태 허용). 총 소요가 3분을 넘으면 경고를 남긴다.
 * <p>
 * 종목 일별 지표는 2026-09-08 MV 에서 테이블로 바꿨다. 전체 재계산이 25분이라 DAILY 는 최근 metric-recompute-days 만 다시 계산하고,
 * WEEKLY 가 전체를 다시 계산해 수정계수 변경 같은 과거 구간 변화를 흡수한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivedMetricRefreshService implements CollectJob {

  static final List<String> REFRESH_ORDER = List.of(
      DerivedViewRefresher.MV_ADJUST_FACTOR,
      DerivedViewRefresher.TB_DAILY_METRIC,
      "mv_stock_index_metric",
      "mv_stock_sector_daily");
  static final long WARN_THRESHOLD_MS = 3 * 60_000L;

  private final DerivedViewRefresher viewRefresher;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.DERIVED_REFRESH;
  }

  /**
   * API 트리거: startDate 가 있으면 그 날짜부터 지표를 다시 계산한다(2015-01-01 이면 사실상 전체). 없으면 증분.
   */
  @Override
  public void execute(CollectExecution execution) {
    refreshAll(execution, execution.request().startDate());
  }

  /**
   * 증분 갱신(DAILY): 지표는 오늘 − metric-recompute-days 부터 다시 계산한다.
   */
  public int refreshAll(CollectExecution execution) {
    return refreshAll(execution, MarketClock.today().minusDays(properties.getDerived().getMetricRecomputeDays()));
  }

  /**
   * 전체 갱신(WEEKLY): 지표 테이블 전체를 다시 계산한다.
   */
  public int refreshAllFull(CollectExecution execution) {
    return refreshAll(execution, null);
  }

  /**
   * 순서대로 갱신하고 소요 시간을 메타데이터에 남긴다.
   *
   * @param metricFrom 지표 재계산 하한 (null 이면 전체)
   * @return 갱신한 객체 수
   */
  int refreshAll(CollectExecution execution, LocalDate metricFrom) {
    KisProperties.Derived derived = properties.getDerived();
    Map<String, Object> timings = new LinkedHashMap<>();
    long total = 0;
    int refreshed = 0;
    execution.putMetadata("metricFrom", metricFrom == null ? "ALL" : metricFrom.toString());
    for (String name : REFRESH_ORDER) {
      if (execution.isCancelRequested()) {
        break;
      }
      boolean table = DerivedViewRefresher.TB_DAILY_METRIC.equals(name);
      if (table ? !viewRefresher.tableExists(name) : !viewRefresher.materializedViewExists(name)) {
        timings.put(name, "MISSING");
        continue;
      }
      long started = System.currentTimeMillis();
      try {
        if (table) {
          LocalDate lookback = metricFrom == null ? null : metricFrom.minusDays(derived.getMetricLookbackDays());
          int rows = viewRefresher.recomputeDailyMetric(metricFrom, lookback, derived.getWorkMem(), derived.getMaxParallelWorkers());
          execution.addRows(rows);
          timings.put(name, System.currentTimeMillis() - started);
        } else {
          timings.put(name, viewRefresher.refresh(name, derived.isConcurrently(), derived.getWorkMem(), derived.getMaxParallelWorkers()));
        }
        total += System.currentTimeMillis() - started;
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
      log.warn("파생 갱신이 3분을 넘었습니다({}ms, metricFrom={})", total, metricFrom);
      execution.putMetadata("warning", "파생 갱신 3분 초과");
    }
    return refreshed;
  }
}
