package kr.hvy.blog.modules.stock.application.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KsdInfoKind;
import kr.hvy.blog.modules.stock.client.dto.KsdInfoPage;
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
  /**
   * 한 기간의 페이지 상한. 2026-09-09 실측: 전 종목 92일 청크가 LIST_INFO 47·DIVIDEND 43·REV_SPLIT 3·BONUS 1 회 상한(당시 30)에 걸려 조용히 잘렸다.
   * 잘리면 기간을 반으로 나눠 다시 받으므로 이 값은 무한 루프 안전장치이자 "잘린 탐침 1회의 호출 비용" 이다 — 너무 작으면 분할이 늘고 너무 크면 낭비.
   */
  static final int MAX_PAGES = 100;

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

  /** 한 번의 collect 동안 분할·반복·잘림 횟수를 센다 (run 메타로 남겨 페이지 크기·연속조회 지원 여부를 운영에서 읽는다). */
  private static final class Tally {

    int splits;
    int repeatedPages;
    int truncatedDays;
  }

  /**
   * [from, to] 를 청크로 나눠 7종을 모두 받는다. 청크가 페이지 상한에 걸리면 기간을 반으로 나눠 다시 받고, 청크 단위 실패는 기록하고 계속한다.
   */
  public void collect(CollectExecution execution, LocalDate from, LocalDate to, String ticker) {
    Map<KsdInfoKind, Integer> counts = new EnumMap<>(KsdInfoKind.class);
    Tally tally = new Tally();
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
        upserted += collectRange(execution, kind, cursor, chunkEnd, ticker, tally);
        cursor = chunkEnd.plusDays(1);
      }
      counts.put(kind, upserted);
      execution.addRows(upserted);
      execution.flush();
    }
    Map<String, Integer> metadata = new java.util.LinkedHashMap<>();
    counts.forEach((k, v) -> metadata.put(k.getCode(), v));
    execution.putMetadata("upsertedByKind", metadata);
    execution.putMetadata("ksdSplits", tally.splits);
    execution.putMetadata("ksdRepeatedPages", tally.repeatedPages);
    execution.putMetadata("ksdTruncatedDays", tally.truncatedDays);
    log.info("기업행사 수집: range={}~{}, ticker={}, counts={}, splits={}, repeatedPages={}, truncatedDays={}",
        from, to, ticker, counts, tally.splits, tally.repeatedPages, tally.truncatedDays);
  }

  /**
   * [from, to] 한 기간을 받아 upsert 한 행 수를 돌려준다. 페이지 상한에 걸리면(잘림) 기간을 반으로 나눠 재귀한다(92일 → 깊이 ≤ 7).
   * 하루 범위에서도 잘리면 받은 행은 저장하되 실패로 기록한다 — 그 날의 행 수가 상한 × 페이지 크기를 넘는 것이므로 MAX_PAGES 를 조정해야 한다.
   * 호출 예외는 그 기간만 실패로 기록하고 0 을 돌려준다.
   */
  private int collectRange(CollectExecution execution, KsdInfoKind kind, LocalDate from, LocalDate to, String ticker,
      Tally tally) {
    if (execution.isCancelRequested()) {
      return 0;
    }
    String target = kind.getCode() + ":" + from;
    KsdInfoPage page;
    try {
      page = marketDataPort.fetchKsdInfo(kind, from, to, ticker, MAX_PAGES, execution.context(target));
    } catch (RuntimeException e) {
      execution.recordFailure(target, e.getMessage());
      log.warn("기업행사 수집 실패: kind={}, range={}~{}, cause={}", kind, from, to, e.getMessage());
      return 0;
    }
    if (page.repeated()) {
      tally.repeatedPages++;
    }
    if (page.truncated() && from.isBefore(to)) {
      tally.splits++;
      LocalDate mid = from.plusDays(ChronoUnit.DAYS.between(from, to) / 2);
      log.info("기업행사 페이지 상한 {} 도달 → 기간 분할: kind={}, {}~{} → ~{} | {}~", MAX_PAGES, kind, from, to, mid, mid.plusDays(1));
      return collectRange(execution, kind, from, mid, ticker, tally)
          + collectRange(execution, kind, mid.plusDays(1), to, ticker, tally);
    }
    List<CorporateActionRow> rows = CorporateActionMapper.fromKsd(kind, page.rows());
    int upserted = actionWriter.upsert(rows);
    if (page.truncated()) {
      tally.truncatedDays++;
      execution.recordFailure(target, "1일 범위에서도 페이지 상한 " + MAX_PAGES + " 초과 — 행 잘림 (페이지 크기 실측 후 MAX_PAGES 조정)");
      log.warn("기업행사 하루치가 페이지 상한을 넘어 잘림: kind={}, date={}, pages={}", kind, from, page.pageCount());
    } else {
      execution.targetDone();
    }
    return upserted;
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
