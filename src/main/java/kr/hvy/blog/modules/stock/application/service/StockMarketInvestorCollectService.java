package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.EnumCodes;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.MarketInvestorRow;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketIndexWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockMarketInvestorWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 시장별 투자자매매동향 일별 수집(MARKET_INVESTOR_BACKFILL 잡 + 일일 증분 MARKET_INVESTOR 단계).
 * <p>
 * FHPTJ04040000 은 기준일 1개를 받고 연속조회가 없으며 호출당 일수는 실측 항목이다. 그래서 날짜 창 페이저 대신
 * tb_stock_index_daily(0001) 영업일 집합을 역순으로 돌며 응답의 최소 일자 직전 영업일로 커서를 옮긴다 —
 * 응답이 1건이든 여러 날이든 같은 코드로 동작하고, 휴장일의 빈 응답을 소급 한계로 오판하지 않는다.
 * 체크포인트 키는 시장(KOSPI/KOSDAQ), 커서는 다음 기준일이다. 영업일 집합 크기가 자연 상한이라 max-windows 는 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMarketInvestorCollectService implements CollectJob {

  /** 연속 빈 응답 허용 횟수. 넘으면 KIS 소급 한계(EXHAUSTED)로 본다 */
  static final int EMPTY_STREAK_LIMIT = 3;
  private static final int RESUMABLE_FETCH_LIMIT = 10;

  private final KisMarketDataPort marketDataPort;
  private final StockMarketInvestorWriter investorWriter;
  private final MarketIndexWriter indexWriter;
  private final CollectCheckpointService checkpointService;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.MARKET_INVESTOR_BACKFILL;
  }

  /**
   * 시장별 순차 백필. 영업일 정본(0001 지수 일봉)이 비어 있으면 INDEX_BACKFILL 이 먼저다.
   */
  @Override
  public void execute(CollectExecution execution) {
    BackfillRequest request = execution.request();
    LocalDate targetStart = Optional.ofNullable(request.startDate()).orElse(properties.getBackfill().getStartDate());
    LocalDate cursorStart = Optional.ofNullable(request.endDate()).orElse(MarketClock.today());
    TreeSet<LocalDate> tradingDays = new TreeSet<>(
        indexWriter.tradeDates(MarketType.KOSPI.getCompositeIndexCode(), targetStart, cursorStart));
    if (tradingDays.isEmpty()) {
      throw new IllegalStateException("영업일 집합(tb_stock_index_daily 0001)이 비어 있습니다. INDEX_BACKFILL 을 먼저 실행하세요");
    }
    List<String> keys = List.of(MarketType.KOSPI.getCode(), MarketType.KOSDAQ.getCode());
    checkpointService.initialize(CollectJobType.MARKET_INVESTOR_BACKFILL, keys, cursorStart, request.reset());
    execution.putMetadata("targets", keys.size());
    execution.putMetadata("tradingDays", tradingDays.size());

    for (StockCollectCheckpoint cp : checkpointService.findResumable(CollectJobType.MARKET_INVESTOR_BACKFILL, RESUMABLE_FETCH_LIMIT)) {
      if (execution.isCancelRequested()) {
        break;
      }
      MarketType market = EnumCodes.fromCode(MarketType.class, cp.getId().getTargetKey());
      backfillOne(execution, cp, market, tradingDays, targetStart);
    }
  }

  /**
   * 일일 증분: 시장별로 오늘 1회.
   */
  public void collectRecent(CollectExecution execution) {
    LocalDate today = MarketClock.today();
    for (MarketType market : MarketType.values()) {
      try {
        List<KisMarketInvestorResponse.Row> rows = marketDataPort.fetchMarketInvestorDaily(market, today,
            execution.context(market.getCode()));
        execution.addRows(investorWriter.upsert(StockRowMapper.toMarketInvestorRows(market, rows)));
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(market.getCode(), e.getMessage());
      }
    }
    execution.flush();
  }

  /**
   * 시장 1개를 커서 이하 최근 영업일부터 targetStart 까지 역순으로 받는다.
   */
  private void backfillOne(CollectExecution execution, StockCollectCheckpoint checkpoint, MarketType market,
      TreeSet<LocalDate> tradingDays, LocalDate targetStart) {
    checkpoint.start(execution.runId());
    StockCollectCheckpoint cp = checkpointService.save(checkpoint);
    LocalDate cursor = Optional.ofNullable(cp.getCursorDate()).orElse(MarketClock.today());
    int emptyStreak = 0;
    long rows = 0;
    int calls = 0;
    try {
      while (!execution.isCancelRequested()) {
        LocalDate day = tradingDays.floor(cursor);
        if (day == null || day.isBefore(targetStart)) {
          cp.markDone();
          break;
        }
        calls++;
        List<MarketInvestorRow> mapped = StockRowMapper.toMarketInvestorRows(market,
            marketDataPort.fetchMarketInvestorDaily(market, day, execution.context(market.getCode())));
        if (mapped.isEmpty()) {
          if (++emptyStreak >= EMPTY_STREAK_LIMIT) {
            cp.markExhausted();
            break;
          }
          cursor = day.minusDays(1);
          cp.advance(cursor, null, null);
          checkpointService.save(cp);
          continue;
        }
        emptyStreak = 0;
        rows += investorWriter.upsert(mapped);
        LocalDate min = mapped.stream().map(MarketInvestorRow::tradeDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate max = mapped.stream().map(MarketInvestorRow::tradeDate).max(LocalDate::compareTo).orElseThrow();
        cursor = min.minusDays(1);
        cp.advance(cursor, min, max);
        checkpointService.save(cp);
      }
      execution.addRows(rows);
      execution.targetDone();
      log.info("시장별 투자자 백필: market={}, status={}, calls={}, rows={}, earliest={}", market, cp.getStatus(), calls, rows,
          cp.getEarliestLoaded());
    } catch (RuntimeException e) {
      cp.markFailed(e.getMessage());
      execution.recordFailure(market.getCode(), e.getMessage());
      log.warn("시장별 투자자 백필 실패: market={}, cause={}", market, e.getMessage());
    } finally {
      checkpointService.save(cp);
      execution.flush();
    }
  }
}
