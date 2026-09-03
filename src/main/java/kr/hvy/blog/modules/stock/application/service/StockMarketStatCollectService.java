package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.dto.KisCreditBalanceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisProgramTradeResponse;
import kr.hvy.blog.modules.stock.client.dto.KisShortSaleResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.MarketStatRows;
import kr.hvy.blog.modules.stock.repository.jdbc.StockMarketStatWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * P1 시장 통계(공매도·신용잔고·프로그램매매) 수집 — MARKET_STAT 잡 + DAILY 단계(kis.stats.enabled).
 * 세 API 모두 최근 N일을 주므로 매일 1회 호출로 정정을 흡수한다. 깊은 소급은 지원하지 않는다(1차 범위 밖).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMarketStatCollectService implements CollectJob {

  private final KisMarketDataPort marketDataPort;
  private final StockMarketStatWriter statWriter;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.MARKET_STAT;
  }

  @Override
  public void execute(CollectExecution execution) {
    List<String> tickers = targetResolver.resolveTickers(execution.request());
    execution.putMetadata("targets", tickers.size());
    collectRecent(execution, tickers);
  }

  /**
   * 종목마다 세 API 를 각각 호출한다. 출처별 실패를 따로 기록해 한 API 장애가 나머지를 막지 않게 한다.
   */
  public void collectRecent(CollectExecution execution, List<String> tickers) {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(properties.getBackfill().getWindowDays() - 1L);
    runner.run(execution, tickers, ticker -> {
      boolean any = false;
      try {
        List<KisShortSaleResponse.Row> rows = marketDataPort.fetchShortSaleDaily(ticker, from, today, execution.context(ticker));
        execution.addRows(statWriter.upsertShortSale(toShortSale(ticker, rows)));
        any = true;
      } catch (RuntimeException e) {
        execution.recordFailure(ticker + ":SHORT_SALE", e.getMessage());
      }
      try {
        List<KisCreditBalanceResponse.Row> rows = marketDataPort.fetchCreditBalanceDaily(ticker, today, execution.context(ticker));
        execution.addRows(statWriter.upsertCreditBalance(toCredit(ticker, rows)));
        any = true;
      } catch (RuntimeException e) {
        execution.recordFailure(ticker + ":CREDIT", e.getMessage());
      }
      try {
        List<KisProgramTradeResponse.Row> rows = marketDataPort.fetchProgramTradeDaily(ticker, today, execution.context(ticker));
        execution.addRows(statWriter.upsertProgramTrade(toProgram(ticker, rows)));
        any = true;
      } catch (RuntimeException e) {
        execution.recordFailure(ticker + ":PROGRAM", e.getMessage());
      }
      if (any) {
        execution.targetDone();
      }
    });
    execution.flush();
  }

  static List<MarketStatRows.ShortSale> toShortSale(String ticker, List<KisShortSaleResponse.Row> rows) {
    List<MarketStatRows.ShortSale> result = new ArrayList<>(rows.size());
    for (KisShortSaleResponse.Row r : rows) {
      LocalDate date = KisValues.date(r.tradeDate());
      if (date != null) {
        result.add(new MarketStatRows.ShortSale(ticker, date, KisValues.longValue(r.shortSaleQty()),
            KisValues.longValue(r.shortSaleAmt()), KisValues.decimal(r.shortSaleVolumeRatio())));
      }
    }
    return result;
  }

  static List<MarketStatRows.CreditBalance> toCredit(String ticker, List<KisCreditBalanceResponse.Row> rows) {
    List<MarketStatRows.CreditBalance> result = new ArrayList<>(rows.size());
    for (KisCreditBalanceResponse.Row r : rows) {
      LocalDate date = KisValues.date(r.dealDate());
      if (date != null) {
        result.add(new MarketStatRows.CreditBalance(ticker, date, KisValues.longValue(r.loanBalanceQty()),
            KisValues.longValue(r.loanBalanceAmt()), KisValues.decimal(r.loanBalanceRate()),
            KisValues.longValue(r.stockLoanBalanceQty())));
      }
    }
    return result;
  }

  static List<MarketStatRows.ProgramTrade> toProgram(String ticker, List<KisProgramTradeResponse.Row> rows) {
    List<MarketStatRows.ProgramTrade> result = new ArrayList<>(rows.size());
    for (KisProgramTradeResponse.Row r : rows) {
      LocalDate date = KisValues.date(r.tradeDate());
      if (date != null) {
        result.add(new MarketStatRows.ProgramTrade(ticker, date, KisValues.longValue(r.netQty()), KisValues.longValue(r.netAmt())));
      }
    }
    return result;
  }
}
