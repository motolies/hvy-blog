package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.dto.KisStockInfoResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.entity.StockMaster;
import kr.hvy.blog.modules.stock.repository.StockMasterRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 종목 기본정보(STOCK_INFO 잡, CTPF1002R): 상장일·상장폐지일·K200 을 마스터에 보강한다.
 * <p>
 * 마스터 파일에 없는 종목코드를 tickers 로 지정해 호출하면 상장폐지 종목이 조회되는지 실측할 수 있다.
 * 상장폐지일이 있는 미지 종목은 비활성 마스터 행으로 만들어 생존편향을 줄인다(삭제 금지 원칙과 같은 방향).
 */
@Slf4j
@Service
public class StockInfoCollectService implements CollectJob {

  private final KisMarketDataPort marketDataPort;
  private final StockMasterRepository masterRepository;
  private final TargetResolver targetResolver;
  private final ConcurrentTargetRunner runner;
  private final TransactionTemplate transactionTemplate;

  public StockInfoCollectService(KisMarketDataPort marketDataPort, StockMasterRepository masterRepository,
      TargetResolver targetResolver, ConcurrentTargetRunner runner, PlatformTransactionManager transactionManager) {
    this.marketDataPort = marketDataPort;
    this.masterRepository = masterRepository;
    this.targetResolver = targetResolver;
    this.runner = runner;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.STOCK_INFO;
  }

  @Override
  public void execute(CollectExecution execution) {
    List<String> tickers = targetResolver.resolveTickers(execution.request());
    List<KisStockInfoResponse.Output> fetched = Collections.synchronizedList(new ArrayList<>());
    runner.run(execution, tickers, ticker -> {
      try {
        KisStockInfoResponse.Output output = marketDataPort.fetchStockInfo(ticker, execution.context(ticker));
        if (output != null && StringUtils.isNotBlank(output.ticker())) {
          fetched.add(output);
        }
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    });
    int applied = transactionTemplate.execute(status -> apply(fetched));
    execution.addRows(applied);
    execution.putMetadata("targets", tickers.size());
    execution.putMetadata("fetched", fetched.size());
    execution.putMetadata("applied", applied);
    execution.flush();
  }

  /**
   * 조회 결과를 마스터에 반영한다. 기존 종목은 상장일 보강·상장폐지 반영, 미지 종목은 상폐일이 있을 때만 비활성 행으로 생성.
   */
  int apply(List<KisStockInfoResponse.Output> outputs) {
    int applied = 0;
    for (KisStockInfoResponse.Output info : outputs) {
      String ticker = info.ticker().trim();
      LocalDate listing = firstDate(info.kospiListingDate(), info.kosdaqListingDate());
      LocalDate delisting = KisValues.date(info.delistingDate());
      StockMaster master = masterRepository.findById(ticker).orElse(null);
      if (master == null) {
        if (delisting == null) {
          continue;
        }
        MarketType market = StringUtils.isNotBlank(info.kosdaqListingDate()) && KisValues.date(info.kosdaqListingDate()) != null
            ? MarketType.KOSDAQ : MarketType.KOSPI;
        masterRepository.save(StockMaster.builder()
            .ticker(ticker)
            .stockName(StringUtils.abbreviate(StringUtils.defaultIfBlank(info.shortName(), info.name()), 100))
            .marketType(market)
            .securityGroup(StringUtils.defaultIfBlank(info.securityGroup(), "ST"))
            .standardCode(StringUtils.trimToNull(info.standardCode()))
            .listingDate(listing)
            .listedShares(KisValues.longValue(info.listedShares()))
            .parValue(KisValues.decimal(info.parValue()))
            .sectorLargeCode(StringUtils.trimToNull(info.sectorLarge()))
            .sectorMidCode(StringUtils.trimToNull(info.sectorMid()))
            .sectorSmallCode(StringUtils.trimToNull(info.sectorSmall()))
            .kospi200(KisValues.flag(info.kospi200()))
            .active(false)
            .delistingDate(delisting)
            .build());
        applied++;
        continue;
      }
      boolean changed = master.enrich(listing, delisting);
      if (changed) {
        applied++;
      }
    }
    return applied;
  }

  private static LocalDate firstDate(String a, String b) {
    LocalDate first = KisValues.date(a);
    return first != null ? first : KisValues.date(b);
  }
}
