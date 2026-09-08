package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KsdInfoKind;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.CorporateActionWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 예탁원 기업행사 수집(CORP_ACTION 잡 + 주간 증분). 전 종목을 기간으로 조회하므로 호출 수가 적다
 * (7종 × 기간 청크). 미래 일정(권리락 예정)도 들어오므로 종료일은 오늘 + 90일까지 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorporateActionCollectService implements CollectJob {

  /** 한 호출의 조회 기간(일). 상한이 문서에 없어 분기 단위로 나눈다 */
  static final int CHUNK_DAYS = 92;
  static final int FUTURE_DAYS = 90;
  private static final int MAX_PAGES = 30;

  private final KisMarketDataPort marketDataPort;
  private final CorporateActionWriter actionWriter;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.CORP_ACTION;
  }

  @Override
  public void execute(CollectExecution execution) {
    LocalDate from = Optional.ofNullable(execution.request().startDate()).orElse(properties.getBackfill().getStartDate());
    LocalDate to = Optional.ofNullable(execution.request().endDate()).orElse(MarketClock.today().plusDays(FUTURE_DAYS));
    String ticker = execution.request().hasTickers() && execution.request().tickers().size() == 1
        ? execution.request().tickers().get(0) : null;
    collect(execution, from, to, ticker);
  }

  /**
   * 주간 증분: 최근 3개월 + 향후 3개월.
   */
  public void collectRecent(CollectExecution execution) {
    LocalDate today = MarketClock.today();
    collect(execution, today.minusDays(FUTURE_DAYS), today.plusDays(FUTURE_DAYS), null);
  }

  /**
   * 기업행사에는 있지만 마스터에 없는 종목코드. STOCK_INFO 로 조회하면 상폐일이 있는 것만 비활성 마스터 행이 된다(생존편향 보강).
   * 수동 REST 가 거부하는 형식(6자 영숫자 밖)은 자동 경로에서도 보내지 않는다.
   */
  public List<String> tickersMissingFromMaster() {
    return actionWriter.tickersMissingFromMaster().stream().filter(BackfillRequest::isTicker).toList();
  }

  /**
   * [from, to] 를 청크로 나눠 7종을 모두 받는다. 청크 단위 실패는 기록하고 계속한다.
   */
  public void collect(CollectExecution execution, LocalDate from, LocalDate to, String ticker) {
    Map<KsdInfoKind, Integer> counts = new EnumMap<>(KsdInfoKind.class);
    for (KsdInfoKind kind : KsdInfoKind.values()) {
      int upserted = 0;
      LocalDate cursor = from;
      while (!cursor.isAfter(to)) {
        if (execution.isCancelRequested()) {
          return;
        }
        LocalDate chunkEnd = cursor.plusDays(CHUNK_DAYS - 1L);
        if (chunkEnd.isAfter(to)) {
          chunkEnd = to;
        }
        String target = kind.getCode() + ":" + cursor;
        try {
          List<Map<String, String>> raw = marketDataPort.fetchKsdInfo(kind, cursor, chunkEnd, ticker, MAX_PAGES,
              execution.context(target));
          List<CorporateActionRow> rows = CorporateActionMapper.fromKsd(kind, raw);
          upserted += actionWriter.upsert(rows);
          execution.targetDone();
        } catch (RuntimeException e) {
          execution.recordFailure(target, e.getMessage());
          log.warn("기업행사 수집 실패: kind={}, range={}~{}, cause={}", kind, cursor, chunkEnd, e.getMessage());
        }
        cursor = chunkEnd.plusDays(1);
      }
      counts.put(kind, upserted);
      execution.addRows(upserted);
      execution.flush();
    }
    Map<String, Integer> metadata = new java.util.LinkedHashMap<>();
    counts.forEach((k, v) -> metadata.put(k.getCode(), v));
    execution.putMetadata("upsertedByKind", metadata);
    log.info("기업행사 수집: range={}~{}, ticker={}, counts={}", from, to, ticker, counts);
  }

  /**
   * 일봉 힌트를 후보로 기록한다 (DAILY 파이프라인).
   */
  public int recordChartHints(List<DailyPriceRow> hints) {
    if (hints.isEmpty()) {
      return 0;
    }
    return actionWriter.upsert(CorporateActionMapper.fromChartHints(hints));
  }
}
