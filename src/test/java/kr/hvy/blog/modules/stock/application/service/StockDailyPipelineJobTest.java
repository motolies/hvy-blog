package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 일일 파이프라인의 단계 규칙: 휴장일 스킵, 단계 실패 격리, PRICE 실패 시 DERIVED 스킵.
 */
class StockDailyPipelineJobTest {

  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final TargetResolver resolver = mock(TargetResolver.class);
  private final StockIndexCollectService index = mock(StockIndexCollectService.class);
  private final StockDailyPriceCollectService price = mock(StockDailyPriceCollectService.class);
  private final StockValuationCollectService valuation = mock(StockValuationCollectService.class);
  private final StockInvestorCollectService investor = mock(StockInvestorCollectService.class);
  private final CorporateActionCollectService corpAction = mock(CorporateActionCollectService.class);
  private final CollectValidationService validation = mock(CollectValidationService.class);
  private final DerivedMetricRefreshService derived = mock(DerivedMetricRefreshService.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  private final StockMarketStatCollectService stats = mock(StockMarketStatCollectService.class);
  private final StockEtfNavCollectService etfNav = mock(StockEtfNavCollectService.class);
  private final StockMarketInvestorCollectService marketInvestor = mock(StockMarketInvestorCollectService.class);
  private final KisProperties properties = new KisProperties();

  private final StockDailyPipelineJob job = new StockDailyPipelineJob(calendar, resolver, index, price, valuation,
      investor, corpAction, validation, derived, stats, etfNav, marketInvestor, properties);

  private CollectExecution execution(BackfillRequest request) {
    StockCollectRun run = StockCollectRun.builder().runId(1L).jobType(CollectJobType.DAILY).triggerType(TriggerType.SCHEDULER).build();
    return new CollectExecution(run, request, runService);
  }

  @BeforeEach
  void setUp() {
    when(resolver.activeTickers()).thenReturn(List.of("005930", "000660"));
    when(index.indexCodes()).thenReturn(List.of("0001"));
    when(price.collectRecent(any(), anyList())).thenReturn(new StockDailyPriceCollectService.RecentCollectResult(2, 200, List.of()));
  }

  @Test
  @DisplayName("휴장일이면 아무 단계도 실행하지 않고 skipped 를 남긴다 (force 면 실행)")
  void skipsHoliday() {
    when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(false);
    CollectExecution exec = execution(BackfillRequest.empty());
    job.execute(exec);
    assertThat(exec.metadataSnapshot()).containsKey("skipped");
    verify(price, never()).collectRecent(any(), anyList());

    CollectExecution forced = execution(new BackfillRequest(null, null, null, null, null, null, null, true));
    job.execute(forced);
    verify(price).collectRecent(eq(forced), anyList());
  }

  @Test
  @DisplayName("한 단계가 실패해도 다음 단계는 실행되고, PRICE 실패 시 DERIVED 만 건너뛴다")
  @SuppressWarnings("unchecked")
  void isolatesStepFailures() {
    when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(true);
    when(price.collectRecent(any(), anyList())).thenThrow(new IllegalStateException("KIS down"));
    CollectExecution exec = execution(BackfillRequest.empty());

    job.execute(exec);

    verify(valuation).collectSnapshot(eq(exec), anyList(), any(LocalDate.class));
    verify(investor).collectRecent(eq(exec), anyList());
    verify(validation).validate(eq(exec), any(LocalDate.class));
    verify(derived, never()).refreshAll(any());
    assertThat(exec.failureCount()).isEqualTo(1);
    assertThat(exec.failures().get(0).target()).isEqualTo("STEP:PRICE");
    List<Map<String, Object>> steps = (List<Map<String, Object>>) exec.metadataSnapshot().get("steps");
    assertThat(steps).extracting(s -> s.get("step")).containsExactly("INDEX", "PRICE", "VALUATION", "INVESTOR", "MARKET_INVESTOR", "ETF_NAV", "STATS", "CA_HINT", "VALIDATE", "DERIVED");
    assertThat(steps.get(1).get("status")).isEqualTo("FAILED");
    assertThat(steps.get(6).get("status")).isEqualTo("OK"); // kis.stats.enabled 기본 true
    assertThat(steps.get(9).get("status")).isEqualTo("SKIPPED");
  }

  @Test
  @DisplayName("정상 경로에서는 10단계가 모두 OK 이고 힌트가 기업행사 후보로 넘어간다 (통계는 기본 on, 끄면 SKIPPED)")
  @SuppressWarnings("unchecked")
  void happyPath() {
    when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(true);
    CollectExecution exec = execution(BackfillRequest.empty());

    job.execute(exec);

    verify(derived).refreshAll(exec);
    verify(corpAction).recordChartHints(anyList());
    verify(stats).collectRecent(eq(exec), anyList());
    assertThat(exec.failureCount()).isZero();

    properties.getStats().setEnabled(false);
    CollectExecution withoutStats = execution(BackfillRequest.empty());
    job.execute(withoutStats);
    verify(stats, never()).collectRecent(eq(withoutStats), anyList());
    List<Map<String, Object>> steps = (List<Map<String, Object>>) withoutStats.metadataSnapshot().get("steps");
    assertThat(steps.get(6).get("status")).isEqualTo("SKIPPED");
  }
}
