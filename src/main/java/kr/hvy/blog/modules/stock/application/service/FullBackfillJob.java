package kr.hvy.blog.modules.stock.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 전체 백필(BACKFILL_ALL): 계획 §4.1 의 12단계를 순서대로 하위 run 으로 실행한다.
 * <p>
 * 하위 잡마다 run 행이 따로 생기므로 단계별 진행·카운터·409 규칙은 그대로다. 한 단계가 실패해도 다음 단계로 넘어가고
 * (체크포인트가 있어 재트리거로 이어받는다), 상위 run 을 취소하면 진행 중인 하위 잡이 종목 경계에서 멈춘 뒤 나머지를 건너뛴다.
 * 호출 수를 공유하는 KIS 한도 때문에 병렬 이득이 없어 순차가 맞다. 전 종목 기준 4~5시간을 예상한다.
 * 오케스트레이터 ↔ 잡 목록의 순환 참조는 ObjectProvider 로 끊는다.
 */
@Slf4j
@Component
public class FullBackfillJob implements CollectJob {

  static final List<CollectJobType> ORDER = List.of(
      CollectJobType.MASTER, CollectJobType.HOLIDAY, CollectJobType.INDEX_BACKFILL, CollectJobType.PRICE_BACKFILL,
      CollectJobType.STOCK_INFO, CollectJobType.CORP_ACTION, CollectJobType.ADJUST_FACTOR,
      CollectJobType.INVESTOR_BACKFILL, CollectJobType.FINANCIAL_BACKFILL, CollectJobType.OVERSEAS_BACKFILL,
      CollectJobType.DERIVED_REFRESH, CollectJobType.VALIDATE);

  private final ObjectProvider<StockCollectOrchestrator> orchestratorProvider;

  public FullBackfillJob(ObjectProvider<StockCollectOrchestrator> orchestratorProvider) {
    this.orchestratorProvider = orchestratorProvider;
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.BACKFILL_ALL;
  }

  @Override
  public void execute(CollectExecution execution) {
    StockCollectOrchestrator orchestrator = orchestratorProvider.getObject();
    List<Map<String, Object>> steps = new ArrayList<>();
    for (CollectJobType type : ORDER) {
      if (execution.checkCancelNow()) {
        steps.add(step(type, null, "CANCELED", 0, 0, 0, 0));
        continue;
      }
      long started = System.currentTimeMillis();
      try {
        StockCollectRun run = orchestrator.trigger(type, subRequest(type, execution.request()), TriggerType.SCHEDULER,
            execution.runId()).run();
        long ms = System.currentTimeMillis() - started;
        execution.addRows(run.getRowsUpserted());
        execution.targetDone();
        steps.add(step(type, run.getRunId(), run.getStatus().getCode(), run.getRowsUpserted(), run.getApiCallCount(),
            run.getApiFailCount(), ms));
        if (run.getStatus() != CollectStatus.SUCCESS) {
          execution.recordFailure(type.getCode(), "run " + run.getRunId() + " " + run.getStatus() + ": "
              + StringUtils.abbreviate(StringUtils.defaultString(run.getErrorMessage()), 200));
        }
        log.info("전체 백필 단계 종료: step={}, runId={}, status={}, rows={}, {}ms", type, run.getRunId(), run.getStatus(),
            run.getRowsUpserted(), ms);
      } catch (CollectAlreadyRunningException e) {
        steps.add(step(type, e.getRunningRunId(), "SKIPPED_RUNNING", 0, 0, 0, System.currentTimeMillis() - started));
        execution.recordFailure(type.getCode(), e.getMessage());
      } catch (RuntimeException e) {
        steps.add(step(type, null, "FAILED", 0, 0, 0, System.currentTimeMillis() - started));
        execution.recordFailure(type.getCode(), e.toString());
        log.error("전체 백필 단계 실패: step={}", type, e);
      }
      execution.flush();
    }
    execution.putMetadata("steps", steps);
  }

  /**
   * 하위 잡에 넘길 요청. 휴장일은 백필 시작일과 무관하게 오늘부터 1년치를 받도록 startDate=오늘로 바꾼다.
   */
  static BackfillRequest subRequest(CollectJobType type, BackfillRequest request) {
    if (type == CollectJobType.HOLIDAY) {
      return new BackfillRequest(MarketClock.today(), null, null, null, null, null, null, null);
    }
    return request;
  }

  private static Map<String, Object> step(CollectJobType type, Long runId, String status, long rows, long apiCalls,
      long apiFails, long ms) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("step", type.getCode());
    step.put("runId", runId);
    step.put("status", status);
    step.put("rows", rows);
    step.put("apiCalls", apiCalls);
    step.put("apiFails", apiFails);
    step.put("ms", ms);
    return step;
  }
}
