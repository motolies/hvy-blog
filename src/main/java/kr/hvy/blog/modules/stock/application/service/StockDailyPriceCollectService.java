package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.StockDailyPriceWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 종목 일봉(원주가) 수집 — 백필(PRICE_BACKFILL/RELOAD)과 일일 증분 공용.
 * <p>
 * 종목 단위 실패는 체크포인트 FAILED + 실패 목록에 남기고 다음 종목으로 넘어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDailyPriceCollectService implements CollectJob {

  private static final int RESUMABLE_FETCH_LIMIT = 10_000;

  private final KisMarketDataPort marketDataPort;
  private final StockDailyPriceWriter priceWriter;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;
  private final CollectCheckpointService checkpointService;
  private final DateWindowPaginator paginator;
  private final KisProperties properties;

  /**
   * 증분 수집 결과. actionHints 는 최근 캔들 중 기업행사 힌트(락·수정주가·분할비율)가 붙은 행이다.
   */
  public record RecentCollectResult(int processed, long rows, List<DailyPriceRow> actionHints) {
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.PRICE_BACKFILL;
  }

  @Override
  public void execute(CollectExecution execution) {
    backfill(execution, CollectJobType.PRICE_BACKFILL, false);
  }

  /**
   * 체크포인트 기반 백필. checkpointJob 으로 체크포인트 네임스페이스를 나눈다(PRICE_BACKFILL / RELOAD).
   *
   * @param forceReset true 면 요청의 resetCheckpoint 와 무관하게 커서를 되돌린다(부분 재적재)
   */
  public void backfill(CollectExecution execution, CollectJobType checkpointJob, boolean forceReset) {
    BackfillRequest request = execution.request();
    List<String> tickers = targetResolver.resolveTickers(request);
    if (tickers.isEmpty()) {
      throw new IllegalStateException("백필 대상 종목이 없습니다. MASTER 잡을 먼저 실행하거나 tickers 를 지정하세요");
    }
    LocalDate targetStart = Optional.ofNullable(request.startDate()).orElse(properties.getBackfill().getStartDate());
    LocalDate cursorStart = Optional.ofNullable(request.endDate()).orElse(MarketClock.today());
    checkpointService.initialize(checkpointJob, tickers, cursorStart, forceReset || request.reset());
    execution.putMetadata("targets", tickers.size());

    Map<String, LocalDate> listingDates = targetResolver.listingDates(tickers);
    Set<String> targets = new HashSet<>(tickers);
    Set<String> attempted = new HashSet<>();
    int batchSize = Math.max(1, properties.getBackfill().getBatchSize());

    while (!execution.isCancelRequested()) {
      List<StockCollectCheckpoint> batch = new ArrayList<>();
      for (StockCollectCheckpoint cp : checkpointService.findResumable(checkpointJob, RESUMABLE_FETCH_LIMIT)) {
        String ticker = cp.getId().getTargetKey();
        if (targets.contains(ticker) && attempted.add(ticker)) {
          batch.add(cp);
          if (batch.size() >= batchSize) {
            break;
          }
        }
      }
      if (batch.isEmpty()) {
        break;
      }
      runner.run(execution, batch, cp -> backfillOne(execution, cp, targetStart, listingDates.get(cp.getId().getTargetKey())));
    }
    execution.putMetadata("attempted", attempted.size());
  }

  /**
   * 일일 증분: 종목마다 최근 1윈도우(약 100영업일)를 원주가로 재수집해 정정을 흡수한다.
   */
  public RecentCollectResult collectRecent(CollectExecution execution, List<String> tickers) {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(properties.getBackfill().getWindowDays() - 1L);
    LocalDate hintSince = today.minusDays(7);
    List<DailyPriceRow> hints = Collections.synchronizedList(new ArrayList<>());
    int before = execution.processedCount();
    long rowsBefore = execution.totalRows();
    runner.run(execution, tickers, ticker -> {
      try {
        List<KisDailyChartResponse.Candle> candles = marketDataPort.fetchDailyCandles(ticker, from, today, true,
            execution.context(ticker));
        List<DailyPriceRow> rows = StockRowMapper.toDailyPriceRows(ticker, candles);
        execution.addRows(priceWriter.upsert(rows));
        for (DailyPriceRow row : rows) {
          if (!row.tradeDate().isBefore(hintSince) && StockRowMapper.hasCorporateActionHint(row)) {
            hints.add(row);
          }
        }
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    });
    execution.flush();
    return new RecentCollectResult(execution.processedCount() - before, execution.totalRows() - rowsBefore,
        List.copyOf(hints));
  }

  private void backfillOne(CollectExecution execution, StockCollectCheckpoint checkpoint, LocalDate targetStart,
      LocalDate listingDate) {
    String ticker = checkpoint.getId().getTargetKey();
    checkpoint.start(execution.runId());
    StockCollectCheckpoint cp = checkpointService.save(checkpoint);
    LocalDate cursor = Optional.ofNullable(cp.getCursorDate()).orElse(MarketClock.today());
    try {
      DateWindowPaginator.Outcome outcome = paginator.paginateBackward(cursor, targetStart, listingDate,
          properties.getBackfill().getWindowDays(), properties.getBackfill().getMaxWindows(),
          (from, to) -> marketDataPort.fetchDailyCandles(ticker, from, to, true, execution.context(ticker)),
          candle -> KisValues.date(candle.tradeDate()),
          candles -> priceWriter.upsert(StockRowMapper.toDailyPriceRows(ticker, candles)),
          (next, earliest, latest, windows) -> {
            cp.advance(next, earliest, latest);
            checkpointService.save(cp);
          });
      StockIndexCollectService.applyOutcome(cp, outcome);
      checkpointService.save(cp);
      execution.addRows(outcome.rows());
      execution.targetDone();
      log.debug("일봉 백필: ticker={}, termination={}, windows={}, rows={}, earliest={}",
          ticker, outcome.termination(), outcome.windows(), outcome.rows(), outcome.earliest());
    } catch (RuntimeException e) {
      cp.markFailed(e.getMessage());
      checkpointService.save(cp);
      execution.recordFailure(ticker, e.getMessage());
      log.warn("일봉 백필 실패: ticker={}, cause={}", ticker, e.getMessage());
    } finally {
      execution.flush();
    }
  }
}
