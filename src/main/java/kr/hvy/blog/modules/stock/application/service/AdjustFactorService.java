package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.client.dto.KisDailyChartResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionSource;
import kr.hvy.blog.modules.stock.domain.code.CorporateActionType;
import kr.hvy.blog.modules.stock.domain.model.AdjustEventRow;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.AdjustEventWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.CorporateActionWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.DerivedViewRefresher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 수정주가 계수 산출(ADJUST_FACTOR 잡): 예탁원 기업행사 → 계수 이벤트 → MV 갱신 → KIS 수정주가와 표본 대조.
 * <p>
 * 원주가 정본 + 계수 분리 모델의 핵심. 계수 단위 오해(배정율 %/주수)는 대조에서 오차로 드러나므로
 * verified=false 인 이벤트가 남아 있으면 소비자가 알 수 있게 run 메타데이터에 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjustFactorService implements CollectJob {

  /** 대조 허용 상대 오차 0.5% */
  static final BigDecimal TOLERANCE = new BigDecimal("0.005");
  static final int DEFAULT_VERIFY_SAMPLE = 20;

  private final CorporateActionWriter actionWriter;
  private final AdjustEventWriter eventWriter;
  private final DerivedViewRefresher viewRefresher;
  private final KisMarketDataPort marketDataPort;
  private final JdbcTemplate jdbcTemplate;
  private final KisProperties properties;

  /** 종목 1개 대조 결과 */
  public record VerifyResult(String ticker, int compared, BigDecimal maxPriceError, BigDecimal maxVolumeError, boolean passed) {
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.ADJUST_FACTOR;
  }

  @Override
  public void execute(CollectExecution execution) {
    int events = deriveEvents();
    execution.addRows(events);
    execution.putMetadata("eventsUpserted", events);
    execution.putMetadata("eventsTotal", eventWriter.count());

    boolean refreshed = refreshFactorView();
    execution.putMetadata("mvRefreshed", refreshed);
    if (!refreshed) {
      execution.putMetadata("warning", "mv_stock_adjust_factor 가 없어 갱신·대조를 건너뜀 (db/stock-derived.sql 적용 필요)");
      execution.targetDone();
      return;
    }

    List<String> sample = execution.request().hasTickers() ? execution.request().tickers()
        : eventWriter.tickersWithEvents(DEFAULT_VERIFY_SAMPLE);
    Map<String, Object> verification = new LinkedHashMap<>();
    int passed = 0;
    for (String ticker : sample) {
      if (execution.isCancelRequested()) {
        break;
      }
      try {
        VerifyResult result = verify(ticker, execution);
        verification.put(ticker, String.format("%s compared=%d price=%s volume=%s", result.passed() ? "OK" : "MISMATCH",
            result.compared(), result.maxPriceError(), result.maxVolumeError()));
        if (result.passed()) {
          passed++;
        }
        execution.targetDone();
      } catch (RuntimeException e) {
        execution.recordFailure(ticker, e.getMessage());
      }
    }
    execution.putMetadata("verifySample", sample.size());
    execution.putMetadata("verifyPassed", passed);
    execution.putMetadata("verification", verification);
    execution.flush();
  }

  /**
   * KSD 기업행사 중 가격 보정 유형을 계수 이벤트로 바꿔 upsert 한다.
   */
  public int deriveEvents() {
    EnumSet<CorporateActionType> types = EnumSet.noneOf(CorporateActionType.class);
    for (CorporateActionType type : CorporateActionType.values()) {
      if (type.isAdjustsPrice()) {
        types.add(type);
      }
    }
    List<CorporateActionRow> actions = actionWriter.find(CorporateActionSource.KSD, types);
    List<AdjustEventRow> events = new ArrayList<>();
    int skipped = 0;
    for (CorporateActionRow action : actions) {
      BigDecimal previousClose = action.actionType() == CorporateActionType.RIGHTS_ISSUE
          ? previousClose(action.ticker(), action.effectiveDate()) : null;
      Optional<AdjustEventRow> event = AdjustFactorCalculator.toEvent(action, previousClose);
      if (event.isPresent()) {
        events.add(event.get());
      } else {
        skipped++;
      }
    }
    int upserted = eventWriter.upsert(events);
    log.info("수정계수 이벤트 산출: actions={}, events={}, upserted={}, skipped={}", actions.size(), events.size(), upserted, skipped);
    return upserted;
  }

  /**
   * MV 가 있으면 CONCURRENTLY 갱신한다.
   */
  public boolean refreshFactorView() {
    if (!viewRefresher.materializedViewExists(DerivedViewRefresher.MV_ADJUST_FACTOR)) {
      log.warn("{} 가 없습니다. db/stock-derived.sql 을 적용하세요", DerivedViewRefresher.MV_ADJUST_FACTOR);
      return false;
    }
    viewRefresher.refresh(DerivedViewRefresher.MV_ADJUST_FACTOR, true);
    return true;
  }

  /**
   * 종목 1개를 KIS 수정주가(FID_ORG_ADJ_PRC=0) 최근 윈도우와 대조한다. 통과 시 이벤트를 verified 로 표시한다.
   * 거래량 오차는 "KIS 가 거래량도 보정하는가"의 실측값으로 메타데이터에만 남긴다.
   */
  public VerifyResult verify(String ticker, CollectExecution execution) {
    LocalDate today = MarketClock.today();
    LocalDate from = today.minusDays(properties.getBackfill().getWindowDays() * 3L);
    List<KisDailyChartResponse.Candle> adjusted = marketDataPort.fetchDailyCandles(ticker, from, today, false,
        execution.context(ticker));
    Map<LocalDate, DerivedViewRefresher.AdjustedClose> ours = new LinkedHashMap<>();
    for (DerivedViewRefresher.AdjustedClose row : viewRefresher.adjustedCloses(ticker, from, today)) {
      ours.put(row.tradeDate(), row);
    }
    BigDecimal maxPriceError = BigDecimal.ZERO;
    BigDecimal maxVolumeError = BigDecimal.ZERO;
    int compared = 0;
    for (KisDailyChartResponse.Candle candle : adjusted) {
      LocalDate date = KisValues.date(candle.tradeDate());
      BigDecimal kisClose = KisValues.decimal(candle.close());
      DerivedViewRefresher.AdjustedClose mine = date == null ? null : ours.get(date);
      if (mine == null || kisClose == null || kisClose.signum() == 0 || mine.adjClose() == null) {
        continue;
      }
      compared++;
      maxPriceError = maxPriceError.max(relativeError(mine.adjClose(), kisClose));
      Long kisVolume = KisValues.longValue(candle.volume());
      if (kisVolume != null && kisVolume > 0 && mine.adjVolume() != null) {
        maxVolumeError = maxVolumeError.max(relativeError(mine.adjVolume(), BigDecimal.valueOf(kisVolume)));
      }
    }
    boolean passed = compared > 0 && maxPriceError.compareTo(TOLERANCE) <= 0;
    eventWriter.markVerified(ticker, passed);
    log.info("수정계수 대조: ticker={}, compared={}, maxPriceError={}, maxVolumeError={}, passed={}",
        ticker, compared, maxPriceError, maxVolumeError, passed);
    return new VerifyResult(ticker, compared, maxPriceError, maxVolumeError, passed);
  }

  private BigDecimal previousClose(String ticker, LocalDate effectiveDate) {
    List<BigDecimal> rows = jdbcTemplate.query(
        "SELECT close_price FROM tb_stock_daily_price WHERE ticker = ? AND trade_date < ? ORDER BY trade_date DESC LIMIT 1",
        (rs, i) -> rs.getBigDecimal(1), ticker, effectiveDate);
    return rows.isEmpty() ? null : rows.get(0);
  }

  static BigDecimal relativeError(BigDecimal mine, BigDecimal reference) {
    return mine.subtract(reference).abs().divide(reference, 8, RoundingMode.HALF_UP);
  }
}
