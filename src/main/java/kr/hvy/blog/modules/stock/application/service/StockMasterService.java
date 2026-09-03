package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.stock.client.masterfile.IndexCodeRecord;
import kr.hvy.blog.modules.stock.client.masterfile.MasterFileDownloader;
import kr.hvy.blog.modules.stock.client.masterfile.MasterFileLayout;
import kr.hvy.blog.modules.stock.client.masterfile.MasterFileParser;
import kr.hvy.blog.modules.stock.client.masterfile.MasterRecord;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.entity.StockMaster;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.domain.model.MasterHistoryRow;
import kr.hvy.blog.modules.stock.domain.model.SectorMapRow;
import kr.hvy.blog.modules.stock.repository.StockMasterRepository;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketIndexMasterWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockMasterHistoryWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockSectorMapWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 종목 마스터 갱신(MASTER 잡): 마스터 파일 3종(kospi/kosdaq/idxcode) → tb_stock_master 덮어쓰기,
 * SCD2 이력, 섹터 매핑, 신규상장 체크포인트 생성. 상폐 종목은 비활성 처리만 하고 삭제하지 않는다.
 * <p>
 * 다운로드(네트워크)는 트랜잭션 밖에서 하고 DB 반영만 한 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
public class StockMasterService implements CollectJob {

  /** 파일이 잘려 내려온 경우 전 종목을 상폐 처리하는 사고를 막는 하한 */
  static final int MIN_EXPECTED_RECORDS = 1_000;
  static final String INDEX_CODE_FILE = "idxcode";

  private final MasterFileDownloader downloader;
  private final MasterFileParser parser;
  private final StockMasterRepository repository;
  private final StockMasterHistoryWriter historyWriter;
  private final StockSectorMapWriter sectorMapWriter;
  private final MarketIndexMasterWriter indexMasterWriter;
  private final CollectCheckpointService checkpointService;
  private final TransactionTemplate transactionTemplate;

  public StockMasterService(MasterFileDownloader downloader, MasterFileParser parser, StockMasterRepository repository,
      StockMasterHistoryWriter historyWriter, StockSectorMapWriter sectorMapWriter,
      MarketIndexMasterWriter indexMasterWriter, CollectCheckpointService checkpointService,
      PlatformTransactionManager transactionManager) {
    this.downloader = downloader;
    this.parser = parser;
    this.repository = repository;
    this.historyWriter = historyWriter;
    this.sectorMapWriter = sectorMapWriter;
    this.indexMasterWriter = indexMasterWriter;
    this.checkpointService = checkpointService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * 갱신 결과 요약.
   */
  public record MasterRefreshResult(int total, int inserted, int updated, int delisted, int historyChanged,
                                    int sectorChanged, int indexCodes, List<String> newTickers,
                                    List<String> delistedTickers) {
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.MASTER;
  }

  @Override
  public void execute(CollectExecution execution) {
    MasterRefreshResult result = refresh();
    execution.addRows(result.total());
    execution.targetDone();
    execution.putMetadata("inserted", result.inserted());
    execution.putMetadata("updated", result.updated());
    execution.putMetadata("delisted", result.delisted());
    execution.putMetadata("historyChanged", result.historyChanged());
    execution.putMetadata("sectorChanged", result.sectorChanged());
    execution.putMetadata("indexCodes", result.indexCodes());
    execution.putMetadata("newTickers", result.newTickers());
    execution.putMetadata("delistedTickers", result.delistedTickers());
  }

  /**
   * 마스터 파일을 내려받아 DB 에 반영한다.
   */
  public MasterRefreshResult refresh() {
    List<IndexCodeRecord> indexCodes = parser.parseIndexCodes(downloader.download(INDEX_CODE_FILE));
    List<MasterRecord> records = new ArrayList<>();
    for (MasterFileLayout layout : MasterFileLayout.values()) {
      records.addAll(parser.parse(downloader.download(layout.getFileName()), layout));
    }
    if (records.size() < MIN_EXPECTED_RECORDS) {
      throw new IllegalStateException("마스터 레코드가 비정상적으로 적습니다(" + records.size()
          + "건). 파일 손상 가능성이 있어 반영하지 않습니다");
    }
    LocalDate today = MarketClock.today();
    MasterRefreshResult result = transactionTemplate.execute(status -> apply(records, indexCodes, today));
    log.info("종목 마스터 갱신: {}", result);
    return result;
  }

  /**
   * 파싱된 레코드를 한 트랜잭션으로 반영한다.
   */
  MasterRefreshResult apply(List<MasterRecord> records, List<IndexCodeRecord> indexCodes, LocalDate today) {
    Map<String, IndexCodeRecord> uniqueIndex = new LinkedHashMap<>();
    for (IndexCodeRecord code : indexCodes) {
      uniqueIndex.putIfAbsent(code.indexCode(), code);
    }
    indexMasterWriter.replaceAll(new ArrayList<>(uniqueIndex.values()));
    Map<String, String> indexNames = new HashMap<>();
    uniqueIndex.values().forEach(c -> indexNames.put(c.indexCode(), c.indexName()));

    Map<String, StockMaster> existing = new HashMap<>();
    repository.findAll().forEach(m -> existing.put(m.getTicker(), m));

    List<StockMaster> inserted = new ArrayList<>();
    List<String> newTickers = new ArrayList<>();
    List<String> delistedTickers = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int updated = 0;
    for (MasterRecord record : records) {
      if (!seen.add(record.ticker())) {
        continue;
      }
      StockMaster master = existing.get(record.ticker());
      if (master == null) {
        master = StockMaster.fromRecord(record);
        inserted.add(master);
        if (record.isStock()) {
          newTickers.add(record.ticker());
        }
      } else if (master.applyFrom(record)) {
        updated++;
      }
    }
    for (StockMaster master : existing.values()) {
      if (!seen.contains(master.getTicker()) && master.deactivate(today)) {
        delistedTickers.add(master.getTicker());
      }
    }
    repository.saveAll(inserted);

    List<StockMaster> all = new ArrayList<>(existing.values());
    all.addAll(inserted);
    int historyChanged = historyWriter.apply(changedHistoryRows(all, today));
    int sectorChanged = sectorMapWriter.sync(desiredSectorRows(all, today, indexNames), today, SectorMapRow.SOURCE_KRX);

    if (!newTickers.isEmpty()) {
      // 신규상장은 다음 일봉 백필이 이어받도록 PENDING 체크포인트만 만든다
      checkpointService.initialize(CollectJobType.PRICE_BACKFILL, newTickers, today, false);
    }
    return new MasterRefreshResult(seen.size(), inserted.size(), updated, delistedTickers.size(), historyChanged,
        sectorChanged, uniqueIndex.size(), newTickers, delistedTickers);
  }

  /**
   * 현재 이력 해시와 다른 종목만 골라 SCD2 행을 만든다.
   */
  private List<MasterHistoryRow> changedHistoryRows(List<StockMaster> masters, LocalDate today) {
    Map<String, String> currentHashes = historyWriter.currentHashes();
    List<MasterHistoryRow> changed = new ArrayList<>();
    for (StockMaster m : masters) {
      MasterHistoryRow row = MasterHistoryRow.of(m.getTicker(), today, m.getStockName(), m.getMarketType(),
          m.getSecurityGroup(), m.getSectorMidCode(), m.isKospi200(), m.isKrx300(), m.isSuspended(),
          m.isAdministrative(), m.isActive(), m.getListedShares());
      if (!row.snapshotHash().equals(currentHashes.get(m.getTicker()))) {
        changed.add(row);
      }
    }
    return changed;
  }

  /**
   * 활성 종목의 지수업종 중분류 → KRX 섹터 매핑 목표 상태.
   */
  private List<SectorMapRow> desiredSectorRows(List<StockMaster> masters, LocalDate today, Map<String, String> indexNames) {
    List<SectorMapRow> rows = new ArrayList<>();
    for (StockMaster m : masters) {
      if (m.isActive() && m.getSectorMidCode() != null) {
        rows.add(new SectorMapRow(m.getTicker(), m.getSectorMidCode(), today,
            indexNames.getOrDefault(m.getSectorMidCode(), m.getSectorMidCode()), SectorMapRow.SOURCE_KRX));
      }
    }
    return rows;
  }
}
