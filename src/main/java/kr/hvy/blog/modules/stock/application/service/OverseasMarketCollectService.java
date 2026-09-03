package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.OverseasSymbol;
import kr.hvy.blog.modules.stock.client.dto.KisOverseasDailyPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisOverseasIndexChartResponse;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.GlobalMarketRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.GlobalMarketWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 해외 참조 지표(지수·환율·ETF·개별주) 수집 — 백필(OVERSEAS_BACKFILL)과 일일 증분 공용. 심볼은 kis.overseas.symbols.
 * 지수·환율은 날짜 윈도우 API, 개별주·ETF 는 기준일 API 라 페이저의 시작일을 무시한다. 개별주는 수정주가(MODP=1) 로 받는다 —
 * 참조 지표라 계수를 따로 관리하지 않고 매일 최근 윈도우를 재수집해 정정을 흡수한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverseasMarketCollectService implements CollectJob {

  private final KisMarketDataPort marketDataPort;
  private final GlobalMarketWriter globalWriter;
  private final CollectCheckpointService checkpointService;
  private final DateWindowPaginator paginator;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.OVERSEAS_BACKFILL;
  }

  @Override
  public void execute(CollectExecution execution) {
    List<OverseasSymbol> symbols = symbols();
    LocalDate targetStart = Optional.ofNullable(execution.request().startDate()).orElse(properties.getBackfill().getStartDate());
    LocalDate cursorStart = Optional.ofNullable(execution.request().endDate()).orElse(MarketClock.today());
    Map<String, OverseasSymbol> byKey = new HashMap<>();
    symbols.forEach(s -> byKey.put(s.key(), s));
    checkpointService.initialize(CollectJobType.OVERSEAS_BACKFILL, byKey.keySet(), cursorStart, execution.request().reset());
    execution.putMetadata("targets", symbols.size());

    for (StockCollectCheckpoint checkpoint : checkpointService.findResumable(CollectJobType.OVERSEAS_BACKFILL, 1_000)) {
      OverseasSymbol symbol = byKey.get(checkpoint.getId().getTargetKey());
      if (symbol == null) {
        continue;
      }
      if (execution.isCancelRequested()) {
        break;
      }
      backfillOne(execution, checkpoint, symbol, targetStart);
    }
  }

  /**
   * 일일 증분: 심볼마다 최근 1윈도우 재수집.
   */
  public void collectRecent(CollectExecution execution) {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(properties.getBackfill().getWindowDays() - 1L);
    for (OverseasSymbol symbol : symbols()) {
      if (execution.isCancelRequested()) {
        break;
      }
      try {
        execution.addRows(globalWriter.upsert(fetch(execution, symbol, from, today)));
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(symbol.key(), e.getMessage());
      }
    }
    execution.flush();
  }

  /**
   * 설정된 심볼 목록.
   */
  public List<OverseasSymbol> symbols() {
    return OverseasSymbol.parseAll(properties.getOverseas().getSymbols());
  }

  private List<GlobalMarketRow> fetch(CollectExecution execution, OverseasSymbol symbol, LocalDate from, LocalDate to) {
    if (symbol.isEquity()) {
      List<KisOverseasDailyPriceResponse.Candle> candles = marketDataPort.fetchOverseasDailyPrices(symbol.exchange(),
          symbol.symbol(), to, execution.context(symbol.key()));
      return toEquityRows(symbol, candles);
    }
    List<KisOverseasIndexChartResponse.Candle> candles = marketDataPort.fetchOverseasIndexCandles(symbol.marketDiv(),
        symbol.symbol(), from, to, execution.context(symbol.key()));
    return toIndexRows(symbol, candles);
  }

  private void backfillOne(CollectExecution execution, StockCollectCheckpoint checkpoint, OverseasSymbol symbol,
      LocalDate targetStart) {
    checkpoint.start(execution.runId());
    StockCollectCheckpoint cp = checkpointService.save(checkpoint);
    LocalDate cursor = Optional.ofNullable(cp.getCursorDate()).orElse(MarketClock.today());
    try {
      DateWindowPaginator.Outcome outcome = paginator.paginateBackward(cursor, targetStart, null,
          properties.getBackfill().getWindowDays(), properties.getBackfill().getMaxWindows(),
          (from, to) -> fetch(execution, symbol, from, to),
          GlobalMarketRow::tradeDate,
          globalWriter::upsert,
          (next, earliest, latest, windows) -> {
            cp.advance(next, earliest, latest);
            checkpointService.save(cp);
          });
      StockIndexCollectService.applyOutcome(cp, outcome);
      checkpointService.save(cp);
      execution.addRows(outcome.rows());
      execution.targetDone();
      log.info("해외 백필: symbol={}, termination={}, windows={}, rows={}, earliest={}",
          symbol.key(), outcome.termination(), outcome.windows(), outcome.rows(), outcome.earliest());
    } catch (RuntimeException e) {
      cp.markFailed(e.getMessage());
      checkpointService.save(cp);
      execution.recordFailure(symbol.key(), e.getMessage());
      log.warn("해외 백필 실패: symbol={}, cause={}", symbol.key(), e.getMessage());
    } finally {
      execution.flush();
    }
  }

  /**
   * 지수·환율 캔들 → 행.
   */
  static List<GlobalMarketRow> toIndexRows(OverseasSymbol symbol, List<KisOverseasIndexChartResponse.Candle> candles) {
    List<GlobalMarketRow> rows = new ArrayList<>(candles.size());
    for (KisOverseasIndexChartResponse.Candle c : candles) {
      LocalDate date = KisValues.date(c.tradeDate());
      var close = KisValues.decimal(c.close());
      if (date == null || close == null) {
        continue;
      }
      rows.add(new GlobalMarketRow(symbol.key(), date, symbol.marketDiv(), symbol.exchange(),
          KisValues.decimal(c.open()), KisValues.decimal(c.high()), KisValues.decimal(c.low()), close,
          KisValues.longValue(c.volume()), null));
    }
    return rows;
  }

  /**
   * 개별주·ETF 캔들 → 행.
   */
  static List<GlobalMarketRow> toEquityRows(OverseasSymbol symbol, List<KisOverseasDailyPriceResponse.Candle> candles) {
    List<GlobalMarketRow> rows = new ArrayList<>(candles.size());
    for (KisOverseasDailyPriceResponse.Candle c : candles) {
      LocalDate date = KisValues.date(c.tradeDate());
      var close = KisValues.decimal(c.close());
      if (date == null || close == null) {
        continue;
      }
      rows.add(new GlobalMarketRow(symbol.key(), date, symbol.marketDiv(), symbol.exchange(),
          KisValues.decimal(c.open()), KisValues.decimal(c.high()), KisValues.decimal(c.low()), close,
          KisValues.longValue(c.volume()), KisValues.decimal(c.changeRate())));
    }
    return rows;
  }
}
