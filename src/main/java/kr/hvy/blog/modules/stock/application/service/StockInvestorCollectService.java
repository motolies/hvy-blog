package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.StockInvestorWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 투자자별 순매수 수집(INVESTOR_BACKFILL 잡 + 일일 증분). 종목별 투자자매매동향(일별) 은 기준일 하나만 받으므로
 * DateWindowPaginator 의 종료일만 기준일로 쓰고 시작일은 무시한다. 소급 깊이는 체크포인트 EXHAUSTED 분포로 실측된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockInvestorCollectService implements CollectJob {

  private static final int RESUMABLE_FETCH_LIMIT = 10_000;
  /** 백필은 기준일당 연속조회 5페이지까지, 증분은 1페이지(최근분)만 */
  static final int BACKFILL_PAGES = 5;
  static final int RECENT_PAGES = 1;

  private final KisMarketDataPort marketDataPort;
  private final StockInvestorWriter investorWriter;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;
  private final CollectCheckpointService checkpointService;
  private final DateWindowPaginator paginator;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.INVESTOR_BACKFILL;
  }

  @Override
  public void execute(CollectExecution execution) {
    BackfillRequest request = execution.request();
    List<String> tickers = targetResolver.resolveTickers(request);
    if (tickers.isEmpty()) {
      throw new IllegalStateException("수급 백필 대상 종목이 없습니다. MASTER 잡을 먼저 실행하세요");
    }
    LocalDate targetStart = Optional.ofNullable(request.startDate()).orElse(properties.getBackfill().getStartDate());
    LocalDate cursorStart = Optional.ofNullable(request.endDate()).orElse(MarketClock.today());
    checkpointService.initialize(CollectJobType.INVESTOR_BACKFILL, tickers, cursorStart, request.reset());
    execution.putMetadata("targets", tickers.size());

    Map<String, LocalDate> listingDates = targetResolver.listingDates(tickers);
    Set<String> targets = new HashSet<>(tickers);
    Set<String> attempted = new HashSet<>();
    int batchSize = Math.max(1, properties.getBackfill().getBatchSize());
    while (!execution.isCancelRequested()) {
      List<StockCollectCheckpoint> batch = new ArrayList<>();
      for (StockCollectCheckpoint cp : checkpointService.findResumable(CollectJobType.INVESTOR_BACKFILL, RESUMABLE_FETCH_LIMIT)) {
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
   * 일일 증분: 종목마다 오늘 기준 1회 호출 (최근 N일 포함) 후 upsert.
   */
  public void collectRecent(CollectExecution execution, List<String> tickers) {
    LocalDate today = MarketClock.today();
    runner.run(execution, tickers, ticker -> {
      try {
        var rows = marketDataPort.fetchInvestorDaily(ticker, today, RECENT_PAGES, execution.context(ticker));
        execution.addRows(investorWriter.upsert(StockRowMapper.toInvestorRows(ticker, rows)));
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    });
    execution.flush();
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
          (from, to) -> marketDataPort.fetchInvestorDaily(ticker, to, BACKFILL_PAGES, execution.context(ticker)),
          row -> KisValues.date(row.tradeDate()),
          rows -> investorWriter.upsert(StockRowMapper.toInvestorRows(ticker, rows)),
          (next, earliest, latest, windows) -> {
            cp.advance(next, earliest, latest);
            checkpointService.save(cp);
          });
      StockIndexCollectService.applyOutcome(cp, outcome);
      checkpointService.save(cp);
      execution.addRows(outcome.rows());
      execution.targetDone();
    } catch (RuntimeException e) {
      cp.markFailed(e.getMessage());
      checkpointService.save(cp);
      execution.recordFailure(ticker, e.getMessage());
      log.warn("수급 백필 실패: ticker={}, cause={}", ticker, e.getMessage());
    } finally {
      execution.flush();
    }
  }
}
