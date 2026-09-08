package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.dto.KisIndexChartResponse;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketIndexMasterWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 시장·업종 지수 일봉 수집 (INDEX_BACKFILL 잡 + 일일 증분). 지수는 수십 개라 순차로 돈다.
 * KOSPI 종합(0001) 의 날짜 집합이 과거 영업일 정본이 되므로 지수 백필이 종목 백필보다 먼저다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockIndexCollectService implements CollectJob {

  /** 지수 마스터가 비어 있을 때의 최소 대상: 코스피·코스닥 종합, KOSPI200 */
  static final List<String> DEFAULT_INDEX_CODES = List.of(
      MarketType.KOSPI.getCompositeIndexCode(), MarketType.KOSDAQ.getCompositeIndexCode(), "2001");
  private static final int RESUMABLE_FETCH_LIMIT = 10_000;

  private final KisMarketDataPort marketDataPort;
  private final MarketIndexWriter indexWriter;
  private final MarketIndexMasterWriter indexMasterWriter;
  private final CollectCheckpointService checkpointService;
  private final DateWindowPaginator paginator;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.INDEX_BACKFILL;
  }

  @Override
  public void execute(CollectExecution execution) {
    List<String> codes = execution.request().hasIndexCodes() ? execution.request().indexCodes() : indexCodes();
    LocalDate targetStart = Optional.ofNullable(execution.request().startDate())
        .orElse(properties.getBackfill().getStartDate());
    LocalDate cursorStart = Optional.ofNullable(execution.request().endDate()).orElse(MarketClock.today());
    checkpointService.initialize(CollectJobType.INDEX_BACKFILL, codes, cursorStart, execution.request().reset());
    execution.putMetadata("targets", codes.size());

    Set<String> targets = new HashSet<>(codes);
    Set<String> attempted = new HashSet<>();
    for (StockCollectCheckpoint checkpoint : checkpointService.findResumable(CollectJobType.INDEX_BACKFILL, RESUMABLE_FETCH_LIMIT)) {
      String code = checkpoint.getId().getTargetKey();
      if (!targets.contains(code) || !attempted.add(code)) {
        continue;
      }
      if (execution.isCancelRequested()) {
        log.info("지수 백필 취소 감지: runId={}", execution.runId());
        break;
      }
      backfillOne(execution, checkpoint, targetStart);
    }
  }

  /**
   * 일일 증분: 최근 1윈도우(약 100영업일)를 재수집해 정정을 흡수한다.
   */
  public void collectRecent(CollectExecution execution, List<String> codes) {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(properties.getBackfill().getWindowDays() - 1L);
    for (String code : codes) {
      if (execution.isCancelRequested()) {
        break;
      }
      try {
        List<KisIndexChartResponse.Candle> candles = marketDataPort.fetchIndexCandles(code, from, today, execution.context(code));
        execution.addRows(indexWriter.upsert(StockRowMapper.toIndexRows(code, candles)));
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(code, e.getMessage());
      }
    }
    execution.flush();
  }

  /**
   * 백필·증분 대상 지수 코드: 지수 마스터의 활성 코드, 비어 있으면 기본 3개.
   */
  public List<String> indexCodes() {
    List<String> codes = indexMasterWriter.activeCodes();
    return codes.isEmpty() ? DEFAULT_INDEX_CODES : codes;
  }

  private void backfillOne(CollectExecution execution, StockCollectCheckpoint checkpoint, LocalDate targetStart) {
    String code = checkpoint.getId().getTargetKey();
    checkpoint.start(execution.runId());
    StockCollectCheckpoint cp = checkpointService.save(checkpoint);
    LocalDate cursor = Optional.ofNullable(cp.getCursorDate()).orElse(MarketClock.today());
    try {
      DateWindowPaginator.Outcome outcome = paginator.paginateBackward(cursor, targetStart, null,
          properties.getBackfill().getWindowDays(), properties.getBackfill().getMaxWindows(),
          (from, to) -> marketDataPort.fetchIndexCandles(code, from, to, execution.context(code)),
          candle -> KisValues.date(candle.tradeDate()),
          candles -> indexWriter.upsert(StockRowMapper.toIndexRows(code, candles)),
          (next, earliest, latest, windows) -> {
            cp.advance(next, earliest, latest);
            checkpointService.save(cp);
          });
      applyOutcome(cp, outcome);
      checkpointService.save(cp);
      execution.addRows(outcome.rows());
      execution.targetDone();
      log.info("지수 백필: code={}, termination={}, windows={}, rows={}, earliest={}",
          code, outcome.termination(), outcome.windows(), outcome.rows(), outcome.earliest());
    } catch (RuntimeException e) {
      cp.markFailed(e.getMessage());
      checkpointService.save(cp);
      execution.recordFailure(code, e.getMessage());
      log.warn("지수 백필 실패: code={}, cause={}", code, e.getMessage());
    } finally {
      execution.flush();
    }
  }

  /**
   * 페이징 종료 사유를 체크포인트 상태로 옮긴다.
   */
  static void applyOutcome(StockCollectCheckpoint checkpoint, DateWindowPaginator.Outcome outcome) {
    switch (outcome.termination()) {
      case REACHED_TARGET -> checkpoint.markDone();
      case EXHAUSTED, EMPTY -> checkpoint.markExhausted();
      case WINDOW_LIMIT -> checkpoint.pause("윈도우 수 상한 도달 (" + outcome.windows() + "), 다음 실행이 커서부터 이어감");
    }
  }
}
