package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.ValuationRow;
import kr.hvy.blog.modules.stock.repository.jdbc.StockValuationWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 밸류에이션 일별 스냅샷(VALUATION 잡 + 일일 증분). 현재가 API 는 당일 값만 주므로 소급이 불가능하고,
 * 장 마감 후 하루 1회 찍어 쌓는다. 장중에 실행하면 그날 스냅샷이 미확정 값으로 남는다(재실행 시 덮어씀).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockValuationCollectService implements CollectJob {

  private final KisMarketDataPort marketDataPort;
  private final StockValuationWriter valuationWriter;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.VALUATION;
  }

  @Override
  public void execute(CollectExecution execution) {
    List<String> tickers = targetResolver.resolveTickers(execution.request());
    LocalDate tradeDate = Optional.ofNullable(execution.request().endDate()).orElse(MarketClock.today());
    execution.putMetadata("targets", tickers.size());
    execution.putMetadata("tradeDate", tradeDate.toString());
    collectSnapshot(execution, tickers, tradeDate);
  }

  /**
   * 종목마다 현재가 1회 호출 → 스냅샷 upsert.
   */
  public void collectSnapshot(CollectExecution execution, List<String> tickers, LocalDate tradeDate) {
    runner.run(execution, tickers, ticker -> {
      try {
        KisPriceResponse.Output output = marketDataPort.fetchPrice(ticker, execution.context(ticker));
        ValuationRow row = StockRowMapper.toValuationRow(ticker, tradeDate, output);
        if (row != null) {
          execution.addRows(valuationWriter.upsert(List.of(row)));
        }
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    });
    execution.flush();
  }
}
