package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.masterfile.MasterFileDownloader;
import kr.hvy.blog.modules.stock.client.masterfile.MasterFileParser;
import kr.hvy.blog.modules.stock.client.masterfile.ThemeCodeRecord;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.entity.StockMaster;
import kr.hvy.blog.modules.stock.domain.model.SectorMapRow;
import kr.hvy.blog.modules.stock.repository.StockMasterRepository;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketIndexMasterWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockMasterHistoryWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockSectorMapWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 테마 매핑 편입: 테마 파일 실패는 마스터 갱신을 막지 않고 THEME 동기화만 건너뛰며, 마스터에 있는 종목만 THEME 행이 된다.
 */
class StockMasterServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

  private final MasterFileDownloader downloader = mock(MasterFileDownloader.class);
  private final MasterFileParser parser = mock(MasterFileParser.class);
  private final StockMasterRepository repository = mock(StockMasterRepository.class);
  private final StockMasterHistoryWriter historyWriter = mock(StockMasterHistoryWriter.class);
  private final StockSectorMapWriter sectorMapWriter = mock(StockSectorMapWriter.class);
  private final MarketIndexMasterWriter indexMasterWriter = mock(MarketIndexMasterWriter.class);
  private final StockMasterService service = new StockMasterService(downloader, parser, repository, historyWriter,
      sectorMapWriter, indexMasterWriter, mock(CollectCheckpointService.class), mock(PlatformTransactionManager.class));

  @Test
  @DisplayName("테마 파일 다운로드·파싱이 실패하면 빈 결과(건너뜀)를 돌려주고 예외를 전파하지 않는다")
  void themeDownloadFailureIsIsolated() {
    when(downloader.download(StockMasterService.THEME_FILE)).thenThrow(new IllegalStateException("404"));

    assertThat(service.downloadThemes()).isEmpty();
  }

  @Test
  @DisplayName("THEME 행은 마스터에 있는 종목만, 테마 N:M 으로 만들고 형식 밖 코드는 skipped 로 센다")
  @SuppressWarnings("unchecked")
  void applySyncsThemeRowsForKnownTickersOnly() {
    when(repository.findAll()).thenReturn(List.of(master("005930"), master("000660")));
    when(historyWriter.currentHashes()).thenReturn(Map.of());
    List<ThemeCodeRecord> themes = List.of(
        new ThemeCodeRecord("001", "반도체", "005930"),
        new ThemeCodeRecord("002", "AI", "A005930"),
        new ThemeCodeRecord("001", "반도체", "000660"),
        new ThemeCodeRecord("001", "반도체", "999999"),        // 마스터에 없음
        new ThemeCodeRecord("003", "이상", "KR7005930003"));   // 형식 밖
    when(sectorMapWriter.sync(anyList(), eq(TODAY), eq(SectorMapRow.SOURCE_THEME))).thenReturn(3);

    StockMasterService.MasterRefreshResult result = service.apply(List.of(), List.of(), themes, TODAY);

    ArgumentCaptor<List<SectorMapRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(sectorMapWriter).sync(rows.capture(), eq(TODAY), eq(SectorMapRow.SOURCE_THEME));
    assertThat(rows.getValue()).extracting(SectorMapRow::ticker, SectorMapRow::sectorCode, SectorMapRow::sectorName)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("005930", "001", "반도체"),
            org.assertj.core.groups.Tuple.tuple("005930", "002", "AI"),
            org.assertj.core.groups.Tuple.tuple("000660", "001", "반도체"));
    assertThat(result.themeChanged()).isEqualTo(3);
    assertThat(result.themeCodes()).isEqualTo(2);
    assertThat(result.themeSkipped()).isEqualTo(2);
  }

  @Test
  @DisplayName("테마 목록이 없으면(실패) THEME 동기화를 호출하지 않는다 — 빈 목록으로 동기화하면 전 테마가 닫히기 때문")
  void applySkipsThemeSyncWhenAbsent() {
    when(repository.findAll()).thenReturn(List.of(master("005930")));
    when(historyWriter.currentHashes()).thenReturn(Map.of());

    StockMasterService.MasterRefreshResult result = service.apply(List.of(), List.of(), null, TODAY);

    verify(sectorMapWriter, never()).sync(anyList(), any(), eq(SectorMapRow.SOURCE_THEME));
    verify(sectorMapWriter).sync(anyList(), eq(TODAY), eq(SectorMapRow.SOURCE_KRX));
    assertThat(result.themeChanged()).isZero();
    assertThat(result.themeSkipped()).isZero();
  }

  @Test
  @DisplayName("KRX 섹터 매핑: 중분류 \"0000\"(미분류, 2026-10-16 이전 저장 행)·null·비활성은 매핑 행을 만들지 않고 실제 코드만 만든다(이름 없으면 코드)")
  void zeroSectorCodeMakesNoKrxRow() {
    List<SectorMapRow> rows = StockMasterService.desiredSectorRows(List.of(
        StockMaster.builder().ticker("011200").stockName("HMM").marketType(MarketType.KOSPI).securityGroup("ST").sectorMidCode("0000").active(true).build(),
        StockMaster.builder().ticker("005930").stockName("삼성전자").marketType(MarketType.KOSPI).securityGroup("ST").sectorMidCode("0013").active(true).build(),
        StockMaster.builder().ticker("000660").stockName("SK하이닉스").marketType(MarketType.KOSPI).securityGroup("ST").sectorMidCode("0013").active(false).build(),
        StockMaster.builder().ticker("000020").stockName("동화약품").marketType(MarketType.KOSPI).securityGroup("ST").active(true).build()),
        TODAY, Map.of("0013", "전기·전자"));
    assertThat(rows).extracting(SectorMapRow::ticker).containsExactly("005930");
    assertThat(rows.getFirst().sectorName()).isEqualTo("전기·전자");
  }

  @Test
  @DisplayName("KRX 섹터 매핑: 비제조업은 중분류가 없어 대분류(0019 운송·창고·0021 금융·KOSDAQ 1013)를 쓰고, 제조·시장 집계(0027·1009)와 \"0000\" 대분류는 매핑하지 않는다")
  void nonManufacturingFallsBackToLargeCode() {
    List<SectorMapRow> rows = StockMasterService.desiredSectorRows(List.of(
        withSector("011200", "0019", null),
        withSector("105560", "0021", null),
        withSector("900001", "0027", null),
        withSector("900002", "1009", null),
        withSector("900003", "1013", null),
        withSector("900004", "0000", null),
        withSector("900005", null, null),
        withSector("900006", "0026", null)),
        TODAY, Map.of("0019", "운송·창고", "0021", "금융", "1013", "운송·창고"));
    assertThat(rows).extracting(SectorMapRow::ticker, SectorMapRow::sectorCode, SectorMapRow::sectorName)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("011200", "0019", "운송·창고"),
            org.assertj.core.groups.Tuple.tuple("105560", "0021", "금융"),
            org.assertj.core.groups.Tuple.tuple("900003", "1013", "운송·창고"),
            org.assertj.core.groups.Tuple.tuple("900006", "0026", "0026"));
  }

  @Test
  @DisplayName("유효 업종 코드는 중분류가 있으면 대분류보다 우선한다(제조업 0027 + 0013 → 0013)")
  void effectiveSectorPrefersMid() {
    assertThat(StockMasterService.effectiveSectorCode(withSector("005930", "0027", "0013"))).isEqualTo("0013");
    assertThat(StockMasterService.effectiveSectorCode(withSector("011200", "0019", "0000"))).isEqualTo("0019");
    assertThat(StockMasterService.effectiveSectorCode(withSector("900001", "0027", null))).isNull();
  }

  private static StockMaster withSector(String ticker, String large, String mid) {
    return StockMaster.builder().ticker(ticker).stockName(ticker).marketType(MarketType.KOSPI).securityGroup("ST")
        .sectorLargeCode(large).sectorMidCode(mid).active(true).build();
  }

  private static StockMaster master(String ticker) {
    return StockMaster.builder().ticker(ticker).stockName(ticker).marketType(MarketType.KOSPI).securityGroup("ST").build();
  }
}
