package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 마스터 파일에 없는 종목코드를 tickers 로 지정해 호출하면 상장폐지 종목이 조회된다(2026-09-07 실측).
 * 상장폐지일이 있는 미지 종목은 비활성 마스터 행으로 만들어 생존편향을 줄인다(삭제 금지 원칙과 같은 방향).
 * <p>
 * 마스터 키는 항상 <b>요청에 쓴 종목코드</b>다. 응답의 {@code pdno} 는 12자 상품번호({@code 00000A082640})라
 * 키로 쓰면 varchar(10) 을 넘고 기존 행도 못 찾는다. 응답 코드가 요청 코드로 끝나지 않으면 metadata {@code pdnoMismatch} 에 센다.
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

  /**
   * 요청한 종목코드와 KIS 응답 쌍. 마스터 키는 ticker(요청값)이고 output.productNo() 는 참고용이다.
   */
  record Fetched(String ticker, KisStockInfoResponse.Output output) {
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.STOCK_INFO;
  }

  /** 조회·반영 결과 (WEEKLY 상폐 보강 단계가 메타데이터 키를 따로 쓰기 위해 돌려준다) */
  public record InfoResult(int targets, int fetched, int applied, int pdnoMismatch) {
  }

  @Override
  public void execute(CollectExecution execution) {
    InfoResult result = collect(execution, targetResolver.resolveTickers(execution.request()));
    execution.putMetadata("targets", result.targets());
    execution.putMetadata("fetched", result.fetched());
    execution.putMetadata("applied", result.applied());
    if (result.pdnoMismatch() > 0) {
      execution.putMetadata("pdnoMismatch", result.pdnoMismatch());
    }
    execution.flush();
  }

  /**
   * 종목 목록을 조회해 마스터에 반영한다. 카운터(행·완료·실패)는 execution 에 쌓고 메타데이터는 호출자가 쓴다.
   */
  public InfoResult collect(CollectExecution execution, List<String> tickers) {
    List<Fetched> fetched = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger mismatch = new AtomicInteger();
    runner.run(execution, tickers, ticker -> {
      try {
        KisStockInfoResponse.Output output = marketDataPort.fetchStockInfo(ticker, execution.context(ticker));
        if (output != null && StringUtils.isNotBlank(output.productNo())) {
          if (!isSameProduct(ticker, output.productNo())) {
            mismatch.incrementAndGet();
            log.warn("주식기본조회 응답 상품번호가 요청 종목코드와 다름: ticker={}, pdno={}", ticker, output.productNo());
          }
          fetched.add(new Fetched(ticker, output));
        }
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    });
    int applied = transactionTemplate.execute(status -> apply(fetched));
    execution.addRows(applied);
    return new InfoResult(tickers.size(), fetched.size(), applied, mismatch.get());
  }

  /**
   * 조회 결과를 마스터에 반영한다. 기존 종목은 상장일 보강·상장폐지 반영, 미지 종목은 상폐일이 있을 때만 비활성 행으로 생성.
   */
  int apply(List<Fetched> outputs) {
    int applied = 0;
    for (Fetched item : outputs) {
      String ticker = item.ticker();
      KisStockInfoResponse.Output info = item.output();
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

  /**
   * 응답 상품번호(12자, 예 00000A082640)가 요청 종목코드(082640)로 끝나는지. 다르면 KIS 가 다른 상품을 돌려준 것이다.
   */
  private static boolean isSameProduct(String ticker, String productNo) {
    return productNo.trim().endsWith(ticker);
  }

  private static LocalDate firstDate(String a, String b) {
    LocalDate first = KisValues.date(a);
    return first != null ? first : KisValues.date(b);
  }
}
