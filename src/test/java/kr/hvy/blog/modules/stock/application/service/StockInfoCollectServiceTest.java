package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisStockInfoResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.entity.StockMaster;
import kr.hvy.blog.modules.stock.repository.StockMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * STOCK_INFO 잡의 마스터 키 규칙: 응답 pdno(12자 상품번호)가 아니라 요청 종목코드로 행을 찾고 만든다.
 * 2026-09-07 실측(동양생명 082640 → pdno 00000A082640)에서 varchar(10) 초과로 드러난 결함의 회귀 테스트.
 */
class StockInfoCollectServiceTest {

  private final KisMarketDataPort marketDataPort = mock(KisMarketDataPort.class);
  private final StockMasterRepository masterRepository = mock(StockMasterRepository.class);
  private final TargetResolver targetResolver = mock(TargetResolver.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  private final StockInfoCollectService service = new StockInfoCollectService(marketDataPort, masterRepository,
      targetResolver, new ConcurrentTargetRunner(new KisProperties()), mock(PlatformTransactionManager.class));

  @Test
  @DisplayName("기존 종목은 요청 코드로 찾아 상장일을 보강한다 (pdno 로는 절대 찾지 않는다)")
  void apply_enrichesExistingMasterByRequestedTicker() {
    StockMaster master = master("005930", "삼성전자");
    when(masterRepository.findById("005930")).thenReturn(Optional.of(master));

    int applied = service.apply(List.of(fetched("005930", output("00000A005930", "KR7005930003", "19750611", "", ""))));

    assertThat(applied).isEqualTo(1);
    assertThat(master.getListingDate()).isEqualTo(LocalDate.of(1975, 6, 11));
    assertThat(master.isActive()).isTrue();
    verify(masterRepository, never()).save(any());
    verify(masterRepository, never()).findById("00000A005930");
  }

  @Test
  @DisplayName("미지 상폐 종목은 요청 코드(6자)를 키로 비활성 행을 만든다 — 실측 재현(동양생명)")
  void apply_createsInactiveRowKeyedByRequestedTicker() {
    when(masterRepository.findById("082640")).thenReturn(Optional.empty());

    int applied = service.apply(List.of(fetched("082640",
        output("00000A082640", "KR7082640004", "20091008", "", "20260831"))));

    assertThat(applied).isEqualTo(1);
    ArgumentCaptor<StockMaster> saved = ArgumentCaptor.forClass(StockMaster.class);
    verify(masterRepository).save(saved.capture());
    StockMaster row = saved.getValue();
    assertThat(row.getTicker()).isEqualTo("082640").hasSize(6);
    assertThat(row.getStandardCode()).isEqualTo("KR7082640004");
    assertThat(row.getStockName()).isEqualTo("동양생명");
    assertThat(row.getMarketType()).isEqualTo(MarketType.KOSPI);
    assertThat(row.isActive()).isFalse();
    assertThat(row.getDelistingDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(row.getListingDate()).isEqualTo(LocalDate.of(2009, 10, 8));
  }

  @Test
  @DisplayName("미지 종목에 상폐일이 없으면 행을 만들지 않는다")
  void apply_skipsUnknownTickerWithoutDelisting() {
    when(masterRepository.findById("999999")).thenReturn(Optional.empty());

    int applied = service.apply(List.of(fetched("999999", output("00000A999999", "KR7999999009", "20200101", "", ""))));

    assertThat(applied).isZero();
    verify(masterRepository, never()).save(any());
  }

  @Test
  @DisplayName("정상 응답(pdno 가 요청 코드로 끝남)은 불일치로 세지 않고 요청 코드로 반영한다")
  void execute_matchingProductNoIsNotCountedAsMismatch() {
    StockMaster master = master("005930", "삼성전자");
    when(targetResolver.resolveTickers(any())).thenReturn(List.of("005930"));
    when(marketDataPort.fetchStockInfo(eq("005930"), any()))
        .thenReturn(output("00000A005930", "KR7005930003", "19750611", "", ""));
    when(masterRepository.findById("005930")).thenReturn(Optional.of(master));
    CollectExecution execution = execution(List.of("005930"));

    service.execute(execution);

    Map<String, Object> metadata = execution.metadataSnapshot();
    assertThat(metadata).containsEntry("fetched", 1).containsEntry("applied", 1).doesNotContainKey("pdnoMismatch");
    assertThat(master.getListingDate()).isEqualTo(LocalDate.of(1975, 6, 11));
    assertThat(execution.failureCount()).isZero();
  }

  @Test
  @DisplayName("응답 상품번호가 요청 코드와 다르면 metadata pdnoMismatch 에 세되 키는 여전히 요청 코드다")
  void execute_countsProductNoMismatchButKeepsRequestedKey() {
    StockMaster master = master("005930", "삼성전자");
    when(targetResolver.resolveTickers(any())).thenReturn(List.of("005930"));
    when(marketDataPort.fetchStockInfo(eq("005930"), any()))
        .thenReturn(output("00000A999999", "KR7999999009", "19750611", "", ""));
    when(masterRepository.findById("005930")).thenReturn(Optional.of(master));
    CollectExecution execution = execution(List.of("005930"));

    service.execute(execution);

    assertThat(execution.metadataSnapshot()).containsEntry("pdnoMismatch", 1);
    verify(masterRepository).findById("005930");
    verify(masterRepository, never()).findById("00000A999999");
    verify(masterRepository, never()).save(any());
  }

  private CollectExecution execution(List<String> tickers) {
    StockCollectRun run = StockCollectRun.builder().runId(7L).jobType(CollectJobType.STOCK_INFO)
        .triggerType(TriggerType.API).build();
    return new CollectExecution(run, BackfillRequest.forTickers(tickers), runService);
  }

  private static StockMaster master(String ticker, String name) {
    return StockMaster.builder().ticker(ticker).stockName(name).marketType(MarketType.KOSPI).securityGroup("ST").build();
  }

  private static StockInfoCollectService.Fetched fetched(String ticker, KisStockInfoResponse.Output output) {
    return new StockInfoCollectService.Fetched(ticker, output);
  }

  /**
   * 실측 응답 형태: pdno 는 12자 상품번호, 날짜는 yyyyMMdd, 결측은 빈 문자열.
   */
  private static KisStockInfoResponse.Output output(String productNo, String standardCode, String kospiListing,
      String kosdaqListing, String delisting) {
    return new KisStockInfoResponse.Output(productNo, "동양생명", "동양생명보험보통주", "STK", "ST", standardCode,
        kospiListing, kosdaqListing, delisting, "N", "002", "", "", "", "", "", "156062581", "5000", "1231", "N", "N");
  }
}
