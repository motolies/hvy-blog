package kr.hvy.blog.modules.stock.application.service;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.FinancialKind;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import kr.hvy.blog.modules.stock.repository.jdbc.StockFinancialWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재무제표 수집(FINANCIAL_BACKFILL 잡 + 주간 재조회). 종목당 6호출(3종 × 연/분기).
 * 값이 바뀐 결산기만 revision_seq+1 로 쌓이므로 주간 재조회는 정정 공시 감지기 역할을 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockFinancialCollectService implements CollectJob {

  private static final int RESUMABLE_FETCH_LIMIT = 10_000;

  private final KisMarketDataPort marketDataPort;
  private final StockFinancialWriter financialWriter;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;
  private final CollectCheckpointService checkpointService;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.FINANCIAL_BACKFILL;
  }

  /**
   * 체크포인트로 재개 가능한 백필. 재무는 페이징이 없어 종목 단위 DONE/FAILED 만 기록한다.
   */
  @Override
  public void execute(CollectExecution execution) {
    List<String> tickers = targetResolver.resolveTickers(execution.request());
    if (tickers.isEmpty()) {
      throw new IllegalStateException("재무 백필 대상 종목이 없습니다. MASTER 잡을 먼저 실행하세요");
    }
    checkpointService.initialize(CollectJobType.FINANCIAL_BACKFILL, tickers, null, execution.request().reset());
    execution.putMetadata("targets", tickers.size());
    java.util.Set<String> targets = new java.util.HashSet<>(tickers);
    List<StockCollectCheckpoint> pending = checkpointService.findResumable(CollectJobType.FINANCIAL_BACKFILL, RESUMABLE_FETCH_LIMIT)
        .stream().filter(cp -> targets.contains(cp.getId().getTargetKey())).toList();
    runner.run(execution, pending, checkpoint -> {
      String ticker = checkpoint.getId().getTargetKey();
      checkpoint.start(execution.runId());
      StockCollectCheckpoint cp = checkpointService.save(checkpoint);
      try {
        execution.addRows(collectOne(execution, ticker));
        cp.markDone();
        execution.targetDone();
      } catch (RuntimeException e) {
        cp.markFailed(e.getMessage());
        execution.recordFailure(ticker, e.getMessage());
      } finally {
        checkpointService.save(cp);
        execution.flush();
      }
    });
  }

  /**
   * 주간 재조회: 체크포인트 없이 전 종목을 다시 받아 정정만 추가한다.
   */
  public void collectAll(CollectExecution execution, List<String> tickers) {
    runner.run(execution, tickers, ticker -> {
      try {
        execution.addRows(collectOne(execution, ticker));
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    });
    execution.flush();
  }

  /**
   * 종목 1개: 연간·분기 각각 3종을 받아 결산기별로 합친 뒤 리비전 규칙으로 저장한다.
   */
  int collectOne(CollectExecution execution, String ticker) {
    int inserted = 0;
    for (boolean quarterly : new boolean[]{false, true}) {
      List<Map<String, String>> income = marketDataPort.fetchFinancial(FinancialKind.INCOME_STATEMENT, ticker, quarterly, execution.context(ticker));
      List<Map<String, String>> balance = marketDataPort.fetchFinancial(FinancialKind.BALANCE_SHEET, ticker, quarterly, execution.context(ticker));
      List<Map<String, String>> ratio = marketDataPort.fetchFinancial(FinancialKind.FINANCIAL_RATIO, ticker, quarterly, execution.context(ticker));
      List<FinancialRow> rows = FinancialRowMapper.merge(ticker, quarterly, income, balance, ratio);
      inserted += financialWriter.apply(ticker, rows);
    }
    return inserted;
  }
}
