package kr.hvy.blog.modules.stock.application.service;

import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주간 파이프라인(WEEKLY, 일요일 03:00 KST): 예탁원 기업행사(±3개월) → 수정계수 재산출·MV 갱신·표본 대조 → 재무 재조회(정정 감지).
 * 재무는 종목당 6호출이라 전 종목이 약 20분 걸리지만 주 1회 새벽이라 감당된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWeeklyPipelineJob implements CollectJob {

  private final TargetResolver targetResolver;
  private final CorporateActionCollectService corporateActionService;
  private final AdjustFactorService adjustFactorService;
  private final StockFinancialCollectService financialService;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.WEEKLY;
  }

  @Override
  public void execute(CollectExecution execution) {
    List<String> tickers = targetResolver.activeTickers();
    execution.putMetadata("targets", tickers.size());
    PipelineSteps steps = new PipelineSteps(execution);
    steps.run("CORP_ACTION", () -> corporateActionService.collectRecent(execution));
    steps.run("ADJUST_FACTOR", () -> adjustFactorService.execute(execution));
    steps.run("FINANCIAL", () -> financialService.collectAll(execution, tickers));
    steps.finish();
  }
}
