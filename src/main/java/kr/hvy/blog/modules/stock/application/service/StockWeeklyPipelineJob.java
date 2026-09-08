package kr.hvy.blog.modules.stock.application.service;

import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주간 파이프라인(WEEKLY, 일요일 03:00 KST): 예탁원 기업행사(±3개월) → 상폐 보강(기업행사에만 있는 종목을 STOCK_INFO 로 조회)
 * → 수정계수 재산출·MV 갱신·표본 대조 → 재무 재조회(정정 감지).
 * 재무는 종목당 12호출(6종 × 연/분기)이라 전 종목이 약 40분 걸리지만 주 1회 새벽이라 감당된다.
 * 상폐 보강 후보 중 상폐일이 없는 코드(KONEX·비상장)는 행이 생기지 않아 매주 다시 조회되지만 수는 유계다(run 메타 stockInfoCandidates).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockWeeklyPipelineJob implements CollectJob {

  private final TargetResolver targetResolver;
  private final CorporateActionCollectService corporateActionService;
  private final AdjustFactorService adjustFactorService;
  private final StockFinancialCollectService financialService;
  private final StockInfoCollectService stockInfoService;

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
    steps.run("STOCK_INFO", () -> {
      List<String> candidates = corporateActionService.tickersMissingFromMaster();
      execution.putMetadata("stockInfoCandidates", candidates.size());
      int applied = candidates.isEmpty() ? 0 : stockInfoService.collect(execution, candidates).applied();
      execution.putMetadata("stockInfoApplied", applied);
    });
    steps.run("ADJUST_FACTOR", () -> adjustFactorService.execute(execution));
    steps.run("FINANCIAL", () -> financialService.collectAll(execution, tickers));
    steps.finish();
  }
}
