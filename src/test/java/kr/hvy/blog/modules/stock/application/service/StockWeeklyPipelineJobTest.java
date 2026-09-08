package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WEEKLY 4단계와 상폐 보강 단계: 기업행사−마스터 차집합을 STOCK_INFO 로 조회하고, 실패해도 다음 단계를 막지 않는다.
 */
class StockWeeklyPipelineJobTest {

  private final TargetResolver targetResolver = mock(TargetResolver.class);
  private final CorporateActionCollectService corpAction = mock(CorporateActionCollectService.class);
  private final AdjustFactorService adjustFactor = mock(AdjustFactorService.class);
  private final StockFinancialCollectService financial = mock(StockFinancialCollectService.class);
  private final StockInfoCollectService stockInfo = mock(StockInfoCollectService.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  private final StockWeeklyPipelineJob job = new StockWeeklyPipelineJob(targetResolver, corpAction, adjustFactor, financial, stockInfo);

  @Test
  @DisplayName("CORP_ACTION → STOCK_INFO → ADJUST_FACTOR → FINANCIAL 순서로 돌고, 후보가 있으면 STOCK_INFO 를 그 목록으로 호출한다")
  @SuppressWarnings("unchecked")
  void runsFourStepsAndEnrichesDelisted() {
    when(targetResolver.activeTickers()).thenReturn(List.of("005930"));
    when(corpAction.tickersMissingFromMaster()).thenReturn(List.of("003410", "085370"));
    when(stockInfo.collect(any(), eq(List.of("003410", "085370"))))
        .thenReturn(new StockInfoCollectService.InfoResult(2, 2, 1, 0));
    CollectExecution exec = execution();

    job.execute(exec);

    List<Map<String, Object>> steps = (List<Map<String, Object>>) exec.metadataSnapshot().get("steps");
    assertThat(steps).extracting(s -> s.get("step")).containsExactly("CORP_ACTION", "STOCK_INFO", "ADJUST_FACTOR", "FINANCIAL");
    assertThat(steps).allSatisfy(s -> assertThat(s.get("status")).isEqualTo("OK"));
    assertThat(exec.metadataSnapshot()).containsEntry("stockInfoCandidates", 2).containsEntry("stockInfoApplied", 1);
    verify(financial).collectAll(eq(exec), eq(List.of("005930")));
  }

  @Test
  @DisplayName("후보가 없으면 STOCK_INFO 를 호출하지 않고 applied 0 을 남긴다")
  void skipsStockInfoWhenNoCandidates() {
    when(corpAction.tickersMissingFromMaster()).thenReturn(List.of());
    CollectExecution exec = execution();

    job.execute(exec);

    verify(stockInfo, never()).collect(any(), anyList());
    assertThat(exec.metadataSnapshot()).containsEntry("stockInfoCandidates", 0).containsEntry("stockInfoApplied", 0);
  }

  @Test
  @DisplayName("STOCK_INFO 단계가 실패해도 ADJUST_FACTOR·FINANCIAL 은 실행된다")
  @SuppressWarnings("unchecked")
  void stockInfoFailureDoesNotBlockLaterSteps() {
    when(corpAction.tickersMissingFromMaster()).thenReturn(List.of("003410"));
    doThrow(new IllegalStateException("KIS 장애")).when(stockInfo).collect(any(), anyList());
    CollectExecution exec = execution();

    job.execute(exec);

    List<Map<String, Object>> steps = (List<Map<String, Object>>) exec.metadataSnapshot().get("steps");
    assertThat(steps.get(1).get("status")).isEqualTo("FAILED");
    assertThat(steps.get(2).get("status")).isEqualTo("OK");
    verify(adjustFactor).execute(exec);
    verify(financial).collectAll(eq(exec), anyList());
    assertThat(exec.failureCount()).isEqualTo(1);
  }

  private CollectExecution execution() {
    StockCollectRun run = StockCollectRun.builder().runId(9L).jobType(CollectJobType.WEEKLY).triggerType(TriggerType.SCHEDULER).build();
    return new CollectExecution(run, BackfillRequest.empty(), runService);
  }
}
