package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.DataQuality;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * ADVISE 게이트: 영업일인가, 이미 판단했는가, 입력 데이터가 준비됐는가(오늘이면 DAILY 완료 + PRICE·DERIVED 단계 OK), 마감을 넘겼는가.
 * <p>
 * 스케줄러는 run 을 만들기 전에 이 판정으로 조용히 돌아가고(5분 간격 폴링이 run 을 쌓지 않게), 잡은 같은 판정을 다시 해 SKIPPED 로 닫는다.
 * 결손일(VALUATION·INVESTOR 등 다른 단계 실패)은 막지 않되 DEGRADED 로 표시해 학습에서 제외한다.
 */
@Service
@RequiredArgsConstructor
public class AdvisorGateService {

  static final List<String> REQUIRED_STEPS = List.of("PRICE", "DERIVED");
  static final int MIN_METRIC_ROWS = 100;

  /** 게이트 판정 */
  public record Decision(boolean tradingDay, boolean alreadyDone, boolean dataReady, boolean pastDeadline, DataQuality quality, String reason) {

    /** 실행 가능 */
    public boolean ready() {
      return tradingDay && !alreadyDone && dataReady;
    }

    /** 스케줄러가 run 없이 조용히 돌아가야 하는 경우 (마감 전 대기·이미 완료·휴장일) */
    public boolean waitQuietly() {
      return !ready() && !(pastDeadline && tradingDay && !alreadyDone);
    }
  }

  private final StockCollectRunRepository collectRuns;
  private final MarketCalendarService calendar;
  private final AdviceWriter adviceWriter;
  private final JdbcTemplate jdbc;
  private final AdvisorProperties properties;

  public Decision decide(LocalDate baseDate) {
    return decide(baseDate, LocalTime.now(MarketClock.KST));
  }

  public Decision decide(LocalDate baseDate, LocalTime nowKst) {
    boolean tradingDay = calendar.isTradingDay(baseDate);
    if (!tradingDay) {
      return new Decision(false, false, false, false, DataQuality.OK, "휴장일 " + baseDate);
    }
    boolean alreadyDone = adviceWriter.find(baseDate, AdviceHeader.KIND_DAILY, AdviceVariant.LIVE).isPresent();
    if (alreadyDone) {
      return new Decision(true, true, true, false, DataQuality.OK, "이미 판단이 있습니다: " + baseDate);
    }
    boolean today = baseDate.equals(MarketClock.today());
    boolean pastDeadline = today && !nowKst.isBefore(properties.getAdvise().getDeadline());
    if (!metricsPresent(baseDate)) {
      return new Decision(true, false, false, pastDeadline, DataQuality.OK, "파생 지표(tb_stock_daily_metric)가 아직 없습니다: " + baseDate);
    }
    if (!today) {
      // 과거 날짜(수동 보충): 데이터가 있으면 진행. DAILY run 이 있으면 품질을 그대로 반영
      return new Decision(true, false, true, false, qualityOf(dailyRun(baseDate)), "과거 기준일 수동 판단");
    }
    Optional<StockCollectRun> run = dailyRun(baseDate);
    if (run.isEmpty()) {
      return new Decision(true, false, false, pastDeadline, DataQuality.OK, "DAILY 수집이 아직 끝나지 않았습니다: " + baseDate);
    }
    if (!requiredStepsOk(run.get())) {
      return new Decision(true, false, false, pastDeadline, DataQuality.DEGRADED, "DAILY 의 PRICE·DERIVED 단계가 실패했습니다: run=" + run.get().getRunId());
    }
    return new Decision(true, false, true, pastDeadline, qualityOf(run), "DAILY 완료");
  }

  private Optional<StockCollectRun> dailyRun(LocalDate date) {
    return collectRuns.findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(CollectJobType.DAILY, date,
        List.of(CollectStatus.SUCCESS, CollectStatus.PARTIAL));
  }

  private boolean metricsPresent(LocalDate date) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_daily_metric WHERE trade_date = ?", Integer.class, date);
    return n != null && n >= MIN_METRIC_ROWS;
  }

  /**
   * DAILY 메타 steps[] 에서 PRICE·DERIVED 가 OK 인지.
   */
  static boolean requiredStepsOk(StockCollectRun run) {
    Map<String, String> steps = stepStatuses(run);
    return REQUIRED_STEPS.stream().allMatch(s -> "OK".equals(steps.get(s)));
  }

  /**
   * 필수 단계 외에 실패·건너뜀 단계가 있으면 DEGRADED (학습 제외 표시).
   */
  static DataQuality qualityOf(Optional<StockCollectRun> run) {
    if (run.isEmpty()) {
      return DataQuality.OK;
    }
    Map<String, String> steps = stepStatuses(run.get());
    boolean degraded = steps.entrySet().stream()
        .anyMatch(e -> !"STATS".equals(e.getKey()) && !"OK".equals(e.getValue()));
    return degraded ? DataQuality.DEGRADED : DataQuality.OK;
  }

  @SuppressWarnings("unchecked")
  static Map<String, String> stepStatuses(StockCollectRun run) {
    Map<String, String> result = new java.util.LinkedHashMap<>();
    Map<String, Object> metadata = run.getMetadataJson();
    if (metadata == null || !(metadata.get("steps") instanceof List<?> steps)) {
      return result;
    }
    for (Object step : steps) {
      if (step instanceof Map<?, ?> m && m.get("step") != null) {
        result.put(String.valueOf(m.get("step")), String.valueOf(m.get("status")));
      }
    }
    return result;
  }
}
