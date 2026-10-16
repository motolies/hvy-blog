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
 * KIS 대조(verify)는 최신 100캔들만 받아 효력일이 오래된 이벤트를 못 보므로, 효력일이 지난 이벤트는 효력일 전후 원주가 비율로도 검산한다
 * (2026-10-16: 디바이스 187870 무상증자 계수 0.0099 가 54배 어긋난 채 verified=false 로 남아 있던 결함).
 * 효력일이 미래인 이벤트는 MV 술어(효력일 <= KST 오늘)가 걸러 현재 시세에 미리 적용되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjustFactorService implements CollectJob {

  /** 대조 허용 상대 오차 0.5% */
  static final BigDecimal TOLERANCE = new BigDecimal("0.005");
  /** 효력일 검산 허용 배수. KRX 가격제한 ±30% 안의 당일 등락은 1.3배 이내이므로 관측 비율/계수가 1.5배를 넘으면 단위·효력일 오해로 본다 */
  static final double PLAUSIBLE_BAND = 1.5;
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
    DeriveResult derived = deriveEvents(MarketClock.today());
    execution.addRows(derived.upserted());
    execution.putMetadata("eventsUpserted", derived.upserted());
    execution.putMetadata("eventsDeferred", derived.deferred());
    execution.putMetadata("eventsImplausible", derived.implausible().size());
    if (!derived.implausible().isEmpty()) {
      execution.putMetadata("implausible", derived.implausible());
    }
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

  /** 계수 이벤트 산출 결과: upsert 된 행, 보류(미래 유상증자), 해석 불가로 건너뛴 행, 효력일 검산 실패(종목/효력일 → 사유) */
  public record DeriveResult(int upserted, int deferred, int skipped, Map<String, String> implausible) {
  }

  /**
   * KSD 기업행사 중 가격 보정 유형을 계수 이벤트로 바꿔 upsert 한다.
   * 비율이 사전에 확정되는 유형은 효력일이 미래여도 미리 만들고(MV 술어가 효력일에 활성화), 유상증자만 asOf 이후 효력분을 보류한다.
   */
  public DeriveResult deriveEvents(LocalDate asOf) {
    EnumSet<CorporateActionType> types = EnumSet.noneOf(CorporateActionType.class);
    for (CorporateActionType type : CorporateActionType.values()) {
      if (type.isAdjustsPrice()) {
        types.add(type);
      }
    }
    List<CorporateActionRow> actions = actionWriter.find(CorporateActionSource.KSD, types);
    List<AdjustEventRow> events = new ArrayList<>();
    Map<String, String> implausible = new LinkedHashMap<>();
    int skipped = 0;
    int deferred = 0;
    for (CorporateActionRow action : actions) {
      if (!derivable(action, asOf)) {
        deferred++;
        continue;
      }
      BigDecimal previousClose = action.actionType() == CorporateActionType.RIGHTS_ISSUE
          ? previousClose(action.ticker(), action.effectiveDate()) : null;
      Optional<AdjustEventRow> event = AdjustFactorCalculator.toEvent(action, previousClose);
      if (event.isEmpty()) {
        skipped++;
        continue;
      }
      String reason = implausibleReason(event.get(), asOf);
      if (reason != null) {
        // 잘못된 계수를 저장하지 않고, 예전 실행이 미리 만들어 둔 행도 지운다 — MV 가 효력일 이전 전 구간에 곱하므로 남겨두면 지표 전체가 오염된다
        eventWriter.delete(event.get().ticker(), event.get().effectiveDate(), event.get().actionType());
        implausible.put(event.get().ticker() + "/" + event.get().effectiveDate(), reason);
        log.warn("수정계수 효력일 검산 실패: {} {}", event.get().ticker(), reason);
        continue;
      }
      events.add(event.get());
    }
    int upserted = eventWriter.upsert(events);
    log.info("수정계수 이벤트 산출: actions={}, events={}, upserted={}, deferred={}, skipped={}, implausible={}",
        actions.size(), events.size(), upserted, deferred, skipped, implausible.size());
    return new DeriveResult(upserted, deferred, skipped, implausible);
  }

  /**
   * 효력일이 지난 이벤트를 효력일 직전 종가 → 효력일(이후 첫 거래일) 종가 비율로 검산한다. 관측 비율/계수가 PLAUSIBLE_BAND 배 밖이면 사유, 통과·검산 불가(가격 없음·미래)면 null.
   */
  String implausibleReason(AdjustEventRow event, LocalDate asOf) {
    if (event.effectiveDate().isAfter(asOf)) {
      return null;
    }
    BigDecimal before = previousClose(event.ticker(), event.effectiveDate());
    BigDecimal after = firstCloseOnOrAfter(event.ticker(), event.effectiveDate());
    if (!plausible(event.priceFactor(), before, after)) {
      return String.format("%s %s factor=%s observed=%s", event.actionType(), event.effectiveDate(),
          event.priceFactor().stripTrailingZeros().toPlainString(), after.divide(before, 4, RoundingMode.HALF_UP).toPlainString());
    }
    return null;
  }

  /**
   * 관측 비율(효력일 종가/직전 종가)이 계수의 1/PLAUSIBLE_BAND ~ PLAUSIBLE_BAND 배 안이면 true. 가격이 없거나 0 이면 검산 불가로 true.
   */
  static boolean plausible(BigDecimal priceFactor, BigDecimal closeBefore, BigDecimal closeAfter) {
    if (priceFactor == null || priceFactor.signum() <= 0 || closeBefore == null || closeBefore.signum() <= 0
        || closeAfter == null || closeAfter.signum() <= 0) {
      return true;
    }
    double ratio = closeAfter.doubleValue() / closeBefore.doubleValue() / priceFactor.doubleValue();
    return ratio >= 1 / PLAUSIBLE_BAND && ratio <= PLAUSIBLE_BAND;
  }

  /**
   * 유상증자는 권리락 전일 종가가 확정된 뒤(효력일 <= asOf)에만 계수를 만든다. 효력 전에는 previousClose 가 최근 종가를 집어 계수가 틀린다.
   * 비율이 사전에 알려진 나머지 유형은 미리 만들고 mv_stock_adjust_factor 의 효력일 술어가 당일에 활성화한다.
   */
  static boolean derivable(CorporateActionRow action, LocalDate asOf) {
    return action.actionType() != CorporateActionType.RIGHTS_ISSUE || !action.effectiveDate().isAfter(asOf);
  }

  /**
   * MV 가 있으면 CONCURRENTLY 갱신한다.
   */
  public boolean refreshFactorView() {
    if (!viewRefresher.materializedViewExists(DerivedViewRefresher.MV_ADJUST_FACTOR)) {
      log.warn("{} 가 없습니다. db/stock-derived.sql 을 적용하세요", DerivedViewRefresher.MV_ADJUST_FACTOR);
      return false;
    }
    viewRefresher.refresh(DerivedViewRefresher.MV_ADJUST_FACTOR, properties.getDerived().isConcurrently(),
        properties.getDerived().getWorkMem(), properties.getDerived().getMaxParallelWorkers());
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

  private BigDecimal firstCloseOnOrAfter(String ticker, LocalDate effectiveDate) {
    List<BigDecimal> rows = jdbcTemplate.query(
        "SELECT close_price FROM tb_stock_daily_price WHERE ticker = ? AND trade_date >= ? ORDER BY trade_date LIMIT 1",
        (rs, i) -> rs.getBigDecimal(1), ticker, effectiveDate);
    return rows.isEmpty() ? null : rows.get(0);
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
