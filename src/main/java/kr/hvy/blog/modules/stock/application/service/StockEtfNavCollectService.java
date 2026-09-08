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
import kr.hvy.blog.modules.stock.client.dto.KisEtfNavResponse;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.StockEtfNavWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ETF NAV 일별 수집(ETF_NAV_BACKFILL 잡 + 일일 증분 ETF_NAV 단계). 대상은 마스터의 활성 ETF(증권그룹 EF)만이다.
 * <p>
 * FHPST02440200 은 1회 100건·연속조회 없음이라 일봉과 같은 날짜 창 페이저 + 체크포인트 구조를 쓴다.
 * ETN(EN)은 마스터 ticker 가 Q 접두 7자라 종목코드 형식과 맞지 않아 범위 밖이다(문서 §12).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockEtfNavCollectService implements CollectJob {

  /** 대상 증권그룹: ETF */
  public static final List<String> ETF_GROUPS = List.of("EF");
  private static final int RESUMABLE_FETCH_LIMIT = 10_000;

  private final KisMarketDataPort marketDataPort;
  private final StockEtfNavWriter navWriter;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;
  private final CollectCheckpointService checkpointService;
  private final DateWindowPaginator paginator;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.ETF_NAV_BACKFILL;
  }

  /**
   * 체크포인트 기반 백필. 요청에 tickers 가 없으면 활성 ETF 전체가 대상이다.
   */
  @Override
  public void execute(CollectExecution execution) {
    BackfillRequest request = execution.request();
    List<String> tickers = targetResolver.resolveTickers(request, ETF_GROUPS);
    if (tickers.isEmpty()) {
      throw new IllegalStateException("ETF NAV 백필 대상이 없습니다. MASTER 잡을 먼저 실행하거나 tickers 를 지정하세요");
    }
    LocalDate targetStart = Optional.ofNullable(request.startDate()).orElse(properties.getBackfill().getStartDate());
    LocalDate cursorStart = Optional.ofNullable(request.endDate()).orElse(MarketClock.today());
    checkpointService.initialize(CollectJobType.ETF_NAV_BACKFILL, tickers, cursorStart, request.reset());
    execution.putMetadata("targets", tickers.size());

    Map<String, LocalDate> listingDates = targetResolver.listingDates(tickers);
    Set<String> targets = new HashSet<>(tickers);
    Set<String> attempted = new HashSet<>();
    int batchSize = Math.max(1, properties.getBackfill().getBatchSize());
    while (!execution.isCancelRequested()) {
      List<StockCollectCheckpoint> batch = new ArrayList<>();
      for (StockCollectCheckpoint cp : checkpointService.findResumable(CollectJobType.ETF_NAV_BACKFILL, RESUMABLE_FETCH_LIMIT)) {
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
   * 일일 증분: ETF 마다 최근 1윈도우(약 100영업일)를 재수집해 NAV 정정을 흡수한다.
   */
  public void collectRecent(CollectExecution execution, List<String> tickers) {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(properties.getBackfill().getWindowDays() - 1L);
    runner.run(execution, tickers, ticker -> {
      try {
        List<KisEtfNavResponse.Row> rows = marketDataPort.fetchEtfNavDaily(ticker, from, today, execution.context(ticker));
        execution.addRows(navWriter.upsert(StockRowMapper.toEtfNavRows(ticker, rows)));
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
          (from, to) -> marketDataPort.fetchEtfNavDaily(ticker, from, to, execution.context(ticker)),
          row -> KisValues.date(row.tradeDate()),
          rows -> navWriter.upsert(StockRowMapper.toEtfNavRows(ticker, rows)),
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
      log.warn("ETF NAV 백필 실패: ticker={}, cause={}", ticker, e.getMessage());
    } finally {
      execution.flush();
    }
  }
}
