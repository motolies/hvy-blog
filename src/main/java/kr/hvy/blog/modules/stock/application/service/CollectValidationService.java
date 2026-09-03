package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 정합성 검증(VALIDATE 잡 + DAILY 단계). 적재가 "성공"해도 데이터가 빠지거나 이상하면 전략이 오염되므로 SQL 로 횡단면을 점검한다.
 * <ul>
 *   <li>행 수: 당일 일봉 vs 활성·비거래정지 종목 수 차이 &gt; 2% → 경고</li>
 *   <li>결측: 직전 영업일에 있었는데 당일 없는 종목(거래정지 제외) → reload 후보</li>
 *   <li>가격 이상치: |등락률| &gt; 30% 인데 ±3일 내 기업행사 기록 없음</li>
 *   <li>OHLC 일관성: low ≤ open,close ≤ high, volume ≥ 0</li>
 *   <li>52주 고가 교차: API 52주 고가 vs 일봉 최고가 편차 &gt; 1% (계수 없는 종목만 → 수정계수 누락 의심)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectValidationService implements CollectJob {

  static final BigDecimal ROW_DIFF_WARN = new BigDecimal("0.02");
  static final BigDecimal OUTLIER_RATE = new BigDecimal("30");
  static final BigDecimal WEEK52_TOLERANCE = new BigDecimal("0.01");
  private static final int LIST_LIMIT = 50;

  private final JdbcTemplate jdbcTemplate;
  private final KisProperties properties;
  private final CollectNotifier notifier;

  /** 검증 결과 */
  public record ValidationReport(LocalDate tradeDate, LocalDate previousTradeDate, int activeCount, int priceCount,
                                 BigDecimal rowDiffRatio, List<String> missing, List<String> outliers,
                                 List<String> ohlcViolations, List<String> week52Mismatch) {

    public int problems() {
      return missing.size() + outliers.size() + ohlcViolations.size() + week52Mismatch.size();
    }

    public boolean rowCountWarning() {
      return rowDiffRatio.compareTo(ROW_DIFF_WARN) > 0;
    }

    public Map<String, Object> toMetadata() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("tradeDate", tradeDate.toString());
      map.put("previousTradeDate", previousTradeDate == null ? null : previousTradeDate.toString());
      map.put("activeCount", activeCount);
      map.put("priceCount", priceCount);
      map.put("rowDiffRatio", rowDiffRatio);
      map.put("missing", missing);
      map.put("outliers", outliers);
      map.put("ohlcViolations", ohlcViolations);
      map.put("week52Mismatch", week52Mismatch);
      return map;
    }

    public String summary() {
      return String.format("[주식 정합성 %s] 활성 %d / 일봉 %d (차이 %.2f%%), 결측 %d, 이상치 %d, OHLC 위반 %d, 52주 편차 %d",
          tradeDate, activeCount, priceCount, rowDiffRatio.doubleValue() * 100, missing.size(), outliers.size(),
          ohlcViolations.size(), week52Mismatch.size());
    }
  }

  @Override
  public CollectJobType jobType() {
    return CollectJobType.VALIDATE;
  }

  @Override
  public void execute(CollectExecution execution) {
    LocalDate date = Optional.ofNullable(execution.request().endDate()).orElse(MarketClock.today());
    validate(execution, date);
  }

  /**
   * 검증을 수행하고 결과를 run 메타데이터·Slack 에 남긴다. 행 수 경고는 #hvy-error, 그 외 문제는 #hvy-notify.
   */
  public ValidationReport validate(CollectExecution execution, LocalDate tradeDate) {
    ValidationReport report = inspect(tradeDate);
    execution.putMetadata("validation", report.toMetadata());
    execution.targetDone();
    log.info("정합성 검증: {}", report.summary());
    if (report.rowCountWarning() && report.priceCount > 0) {
      notifier.notifyText(report.summary() + "\n결측 표본: " + report.missing().subList(0, Math.min(5, report.missing().size())), true);
    } else if (report.problems() > 0) {
      notifier.notifyText(report.summary(), false);
    }
    return report;
  }

  /**
   * SQL 점검만 수행한다 (알림 없음).
   */
  public ValidationReport inspect(LocalDate tradeDate) {
    List<String> groups = properties.getBackfill().getSecurityGroups();
    String groupList = String.join(",", groups.stream().map(g -> "'" + g.replace("'", "") + "'").toList());
    int active = count("SELECT COUNT(*) FROM tb_stock_master WHERE is_active = TRUE AND is_suspended = FALSE "
        + "AND security_group IN (" + groupList + ")");
    int priced = count("SELECT COUNT(*) FROM tb_stock_daily_price p JOIN tb_stock_master m ON m.ticker = p.ticker "
        + "WHERE p.trade_date = ? AND m.is_active = TRUE AND m.security_group IN (" + groupList + ")", tradeDate);
    BigDecimal ratio = active == 0 ? BigDecimal.ZERO
        : BigDecimal.valueOf(Math.abs(active - priced)).divide(BigDecimal.valueOf(active), 4, RoundingMode.HALF_UP);

    LocalDate previous = previousTradeDate(tradeDate);
    List<String> missing = previous == null ? List.of() : jdbcTemplate.query(
        "SELECT p.ticker FROM tb_stock_daily_price p JOIN tb_stock_master m ON m.ticker = p.ticker "
            + "WHERE p.trade_date = ? AND m.is_active = TRUE AND m.is_suspended = FALSE "
            + "AND NOT EXISTS (SELECT 1 FROM tb_stock_daily_price q WHERE q.ticker = p.ticker AND q.trade_date = ?) "
            + "ORDER BY p.ticker LIMIT " + LIST_LIMIT,
        (rs, i) -> rs.getString(1), previous, tradeDate);

    List<String> outliers = jdbcTemplate.query(
        "SELECT p.ticker || ' ' || p.change_rate FROM tb_stock_daily_price p WHERE p.trade_date = ? "
            + "AND ABS(p.change_rate) > ? AND NOT EXISTS (SELECT 1 FROM tb_stock_corporate_action a "
            + "WHERE a.ticker = p.ticker AND a.effective_date BETWEEN ? AND ?) ORDER BY ABS(p.change_rate) DESC LIMIT " + LIST_LIMIT,
        (rs, i) -> rs.getString(1), tradeDate, OUTLIER_RATE, tradeDate.minusDays(3), tradeDate.plusDays(3));

    List<String> ohlc = jdbcTemplate.query(
        "SELECT ticker FROM tb_stock_daily_price WHERE trade_date = ? AND (low_price > LEAST(open_price, close_price) "
            + "OR high_price < GREATEST(open_price, close_price) OR volume < 0) ORDER BY ticker LIMIT " + LIST_LIMIT,
        (rs, i) -> rs.getString(1), tradeDate);

    List<String> week52 = jdbcTemplate.query(
        "SELECT v.ticker || ' api=' || v.week52_high || ' calc=' || h.max_high FROM tb_stock_valuation_daily v "
            + "JOIN (SELECT ticker, MAX(high_price) AS max_high FROM tb_stock_daily_price "
            + "      WHERE trade_date BETWEEN ? AND ? GROUP BY ticker) h ON h.ticker = v.ticker "
            + "WHERE v.trade_date = ? AND v.week52_high IS NOT NULL AND v.week52_high > 0 "
            + "AND NOT EXISTS (SELECT 1 FROM tb_stock_adjust_event e WHERE e.ticker = v.ticker) "
            + "AND ABS(h.max_high - v.week52_high) / v.week52_high > ? ORDER BY v.ticker LIMIT " + LIST_LIMIT,
        (rs, i) -> rs.getString(1), tradeDate.minusDays(365), tradeDate, tradeDate, WEEK52_TOLERANCE);

    return new ValidationReport(tradeDate, previous, active, priced, ratio, missing, outliers, ohlc, week52);
  }

  /**
   * 직전 영업일: KOSPI 종합 지수 일봉의 날짜 집합(정본)에서 찾고, 없으면 주말만 피한 전일.
   */
  LocalDate previousTradeDate(LocalDate tradeDate) {
    List<LocalDate> rows = jdbcTemplate.query(
        "SELECT MAX(trade_date) FROM tb_stock_index_daily WHERE index_code = '0001' AND trade_date < ?",
        (rs, i) -> rs.getObject(1, LocalDate.class), tradeDate);
    if (!rows.isEmpty() && rows.get(0) != null) {
      return rows.get(0);
    }
    LocalDate cursor = tradeDate.minusDays(1);
    while (!MarketCalendarService.isWeekday(cursor)) {
      cursor = cursor.minusDays(1);
    }
    return cursor;
  }

  private int count(String sql, Object... args) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }
}
