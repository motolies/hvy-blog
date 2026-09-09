package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 파생 갱신 순서와 지표 테이블 재계산 범위: DAILY 는 최근 N일(+lookback), WEEKLY 는 전체, API startDate 는 그 날짜부터.
 */
class DerivedMetricRefreshServiceTest {

  private final DerivedViewRefresher refresher = mock(DerivedViewRefresher.class);
  private final KisProperties properties = new KisProperties();
  private final CollectRunService runService = mock(CollectRunService.class);
  private final DerivedMetricRefreshService service = new DerivedMetricRefreshService(refresher, properties);

  @BeforeEach
  void setUp() {
    when(refresher.materializedViewExists(anyString())).thenReturn(true);
    when(refresher.tableExists(DerivedViewRefresher.TB_DAILY_METRIC)).thenReturn(true);
    when(refresher.refresh(anyString(), anyBoolean(), any(), any())).thenReturn(10L);
    when(refresher.recomputeDailyMetric(any(), any(), any(), any())).thenReturn(1234);
  }

  @Test
  @DisplayName("증분(DAILY): 오늘 − recompute-days 부터 쓰고 입력은 그보다 lookback-days 앞부터 읽는다")
  @SuppressWarnings("unchecked")
  void incrementalRecomputeWindow() {
    CollectExecution exec = execution(BackfillRequest.empty());

    int refreshed = service.refreshAll(exec);

    LocalDate from = MarketClock.today().minusDays(140);
    verify(refresher).recomputeDailyMetric(eq(from), eq(from.minusDays(420)), eq("512MB"), eq(0));
    verify(refresher).refresh(eq(DerivedViewRefresher.MV_ADJUST_FACTOR), eq(false), eq("512MB"), eq(0));
    verify(refresher).refresh(eq("mv_stock_sector_daily"), eq(false), eq("512MB"), eq(0));
    assertThat(refreshed).isEqualTo(4);
    assertThat(exec.totalRows()).isEqualTo(1234);
    Map<String, Object> timings = (Map<String, Object>) exec.metadataSnapshot().get("refreshMs");
    assertThat(timings).containsKeys("mv_stock_adjust_factor", "tb_stock_daily_metric", "mv_stock_index_metric", "mv_stock_sector_daily");
    assertThat(exec.metadataSnapshot()).containsEntry("metricFrom", from.toString());
  }

  @Test
  @DisplayName("전체(WEEKLY) 는 하한 없이, API startDate 는 그 날짜부터 재계산한다")
  void fullAndExplicitStart() {
    service.refreshAllFull(execution(BackfillRequest.empty()));
    verify(refresher).recomputeDailyMetric(isNull(), isNull(), eq("512MB"), eq(0));

    LocalDate start = LocalDate.of(2015, 1, 1);
    service.execute(execution(new BackfillRequest(start, null, null, null, null, null, null, null)));
    verify(refresher).recomputeDailyMetric(eq(start), eq(start.minusDays(420)), eq("512MB"), eq(0));
  }

  @Test
  @DisplayName("API 본문 없는 호출은 DAILY 와 같은 증분이고, force:true 만 전체 재계산이다 (2026-09-09 본문 없는 호출이 전체로 돌던 결함)")
  void executeDefaultsToIncrementalAndForceMeansFull() {
    CollectExecution incremental = execution(BackfillRequest.empty());
    service.execute(incremental);
    LocalDate from = MarketClock.today().minusDays(140);
    verify(refresher).recomputeDailyMetric(eq(from), eq(from.minusDays(420)), eq("512MB"), eq(0));
    assertThat(incremental.metadataSnapshot()).containsEntry("metricFrom", from.toString());

    CollectExecution full = execution(new BackfillRequest(LocalDate.of(2015, 1, 1), null, null, null, null, null, null, true));
    service.execute(full);
    verify(refresher).recomputeDailyMetric(isNull(), isNull(), eq("512MB"), eq(0));
    assertThat(full.metadataSnapshot()).containsEntry("metricFrom", "ALL");
  }

  @Test
  @DisplayName("지표 테이블이 없으면(psql 미적용) MISSING 으로 남기고 나머지는 계속 간다")
  void missingTableIsSkipped() {
    when(refresher.tableExists(DerivedViewRefresher.TB_DAILY_METRIC)).thenReturn(false);
    CollectExecution exec = execution(BackfillRequest.empty());

    int refreshed = service.refreshAll(exec);

    assertThat(refreshed).isEqualTo(3);
    @SuppressWarnings("unchecked")
    Map<String, Object> timings = (Map<String, Object>) exec.metadataSnapshot().get("refreshMs");
    assertThat(timings).containsEntry("tb_stock_daily_metric", "MISSING");
  }

  private CollectExecution execution(BackfillRequest request) {
    StockCollectRun run = StockCollectRun.builder().runId(11L).jobType(CollectJobType.DERIVED_REFRESH).triggerType(TriggerType.API).build();
    return new CollectExecution(run, request, runService);
  }
}
