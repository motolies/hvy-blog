package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 일일 증분 파이프라인(DAILY, 평일 18:30 KST). 단계 순서가 단일 출처다:
 * INDEX → PRICE → VALUATION → INVESTOR → ETF_NAV → STATS(kis.stats.enabled) → CA_HINT → VALIDATE → DERIVED.
 * 단계 실패는 다음 단계를 막지 않지만, PRICE 실패 시 DERIVED 는 건너뛴다(잘못된 가격 위에 지표를 만들지 않는다).
 * 휴장일이면 run 기록만 남기고 끝낸다(force 로 무시 가능).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockDailyPipelineJob implements CollectJob {

  private final MarketCalendarService calendarService;
  private final TargetResolver targetResolver;
  private final StockIndexCollectService indexService;
  private final StockDailyPriceCollectService priceService;
  private final StockValuationCollectService valuationService;
  private final StockInvestorCollectService investorService;
  private final CorporateActionCollectService corporateActionService;
  private final CollectValidationService validationService;
  private final DerivedMetricRefreshService derivedRefreshService;
  private final StockMarketStatCollectService marketStatService;
  private final StockEtfNavCollectService etfNavService;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.DAILY;
  }

  @Override
  public void execute(CollectExecution execution) {
    LocalDate today = MarketClock.today();
    if (!execution.request().isForce() && !calendarService.isTradingDay(today)) {
      execution.putMetadata("skipped", "휴장일 " + today);
      log.info("일일 수집 건너뜀(휴장일): {}", today);
      return;
    }
    List<String> tickers = targetResolver.activeTickers();
    execution.putMetadata("targets", tickers.size());
    PipelineSteps steps = new PipelineSteps(execution);
    List<DailyPriceRow> hints = new ArrayList<>();

    steps.run("INDEX", () -> indexService.collectRecent(execution, indexService.indexCodes()));
    boolean priceOk = steps.run("PRICE", () -> hints.addAll(priceService.collectRecent(execution, tickers).actionHints()));
    steps.run("VALUATION", () -> valuationService.collectSnapshot(execution, tickers, today));
    steps.run("INVESTOR", () -> investorService.collectRecent(execution, tickers));
    steps.run("ETF_NAV", () -> etfNavService.collectRecent(execution, targetResolver.activeTickers(StockEtfNavCollectService.ETF_GROUPS)));
    if (properties.getStats().isEnabled()) {
      steps.run("STATS", () -> marketStatService.collectRecent(execution, tickers));
    } else {
      steps.skip("STATS", "kis.stats.enabled=false");
    }
    steps.run("CA_HINT", () -> {
      int recorded = corporateActionService.recordChartHints(hints);
      execution.putMetadata("chartHints", recorded);
    });
    steps.run("VALIDATE", () -> validationService.validate(execution, today));
    if (priceOk) {
      steps.run("DERIVED", () -> derivedRefreshService.refreshAll(execution));
    } else {
      steps.skip("DERIVED", "PRICE 단계 실패");
    }
    steps.finish();
  }
}
