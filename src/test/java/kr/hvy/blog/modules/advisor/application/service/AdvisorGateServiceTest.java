package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.StockCollectRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * 게이트 판정을 고정한다: 휴장일·이미 판단·데이터 없음·DAILY 미완료(마감 전 대기/후 경보)·필수 단계 실패·결손일 DEGRADED·과거 날짜 수동.
 */
class AdvisorGateServiceTest {

  private final StockCollectRunRepository runs = mock(StockCollectRunRepository.class);
  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final LocalDate today = MarketClock.today();
  private AdvisorGateService gate;

  @BeforeEach
  void setUp() {
    gate = new AdvisorGateService(runs, calendar, adviceWriter, jdbc, properties);
    when(calendar.isTradingDay(any())).thenReturn(true);
    when(adviceWriter.find(any(), anyString(), any())).thenReturn(Optional.empty());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2700);
  }

  @Test
  @DisplayName("휴장일이면 실행하지 않고 조용히 대기한다")
  void holiday() {
    when(calendar.isTradingDay(today)).thenReturn(false);
    AdvisorGateService.Decision d = gate.decide(today, LocalTime.of(19, 30));
    assertThat(d.ready()).isFalse();
    assertThat(d.waitQuietly()).isTrue();
    assertThat(d.reason()).contains("휴장일");
  }

  @Test
  @DisplayName("이미 LIVE 판단이 있으면 멱등 종료")
  void alreadyDone() {
    when(adviceWriter.find(today, AdviceHeader.KIND_DAILY, AdviceVariant.LIVE)).thenReturn(Optional.of(AdviceHeader.builder().adviceId(1L).build()));
    AdvisorGateService.Decision d = gate.decide(today, LocalTime.of(19, 30));
    assertThat(d.alreadyDone()).isTrue();
    assertThat(d.ready()).isFalse();
    assertThat(d.waitQuietly()).isTrue();
  }

  @Test
  @DisplayName("오늘 DAILY 가 안 끝났으면 마감 전엔 조용히 대기, 마감 후엔 경보 대상")
  void dailyNotFinished() {
    when(runs.findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(eq(CollectJobType.DAILY), eq(today), any())).thenReturn(Optional.empty());
    AdvisorGateService.Decision before = gate.decide(today, LocalTime.of(19, 40));
    assertThat(before.ready()).isFalse();
    assertThat(before.pastDeadline()).isFalse();
    assertThat(before.waitQuietly()).isTrue();

    AdvisorGateService.Decision after = gate.decide(today, LocalTime.of(19, 55));
    assertThat(after.ready()).isFalse();
    assertThat(after.pastDeadline()).isTrue();
    assertThat(after.waitQuietly()).as("마감 후엔 run 을 만들어 SKIPPED + 경보").isFalse();
  }

  @Test
  @DisplayName("DAILY 가 끝났고 PRICE·DERIVED 가 OK 면 준비 완료, 다른 단계 실패는 DEGRADED 로 표시")
  void dailyFinished() {
    when(runs.findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(eq(CollectJobType.DAILY), eq(today), any()))
        .thenReturn(Optional.of(run(List.of(step("PRICE", "OK"), step("VALUATION", "FAILED"), step("DERIVED", "OK"), step("STATS", "SKIPPED")))));
    AdvisorGateService.Decision d = gate.decide(today, LocalTime.of(19, 30));
    assertThat(d.ready()).isTrue();
    assertThat(d.quality()).isEqualTo(DataQuality.DEGRADED);

    when(runs.findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(eq(CollectJobType.DAILY), eq(today), any()))
        .thenReturn(Optional.of(run(List.of(step("PRICE", "OK"), step("DERIVED", "OK"), step("STATS", "SKIPPED")))));
    assertThat(gate.decide(today, LocalTime.of(19, 30)).quality()).as("STATS 건너뜀은 설정이라 정상").isEqualTo(DataQuality.OK);

    when(runs.findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(eq(CollectJobType.DAILY), eq(today), any()))
        .thenReturn(Optional.of(run(List.of(step("PRICE", "FAILED"), step("DERIVED", "SKIPPED")))));
    AdvisorGateService.Decision broken = gate.decide(today, LocalTime.of(19, 56));
    assertThat(broken.ready()).isFalse();
    assertThat(broken.reason()).contains("PRICE·DERIVED");
  }

  @Test
  @DisplayName("파생 지표 행이 없으면 준비되지 않음, 과거 날짜는 데이터만 있으면 진행")
  void dataPresence() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    assertThat(gate.decide(today, LocalTime.of(19, 30)).ready()).isFalse();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2700);
    LocalDate past = today.minusDays(7);
    when(runs.findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(eq(CollectJobType.DAILY), eq(past), any())).thenReturn(Optional.empty());
    AdvisorGateService.Decision d = gate.decide(past, LocalTime.of(10, 0));
    assertThat(d.ready()).isTrue();
    assertThat(d.pastDeadline()).isFalse();
    assertThat(d.quality()).isEqualTo(DataQuality.OK);
  }

  private static StockCollectRun run(List<Map<String, Object>> steps) {
    return StockCollectRun.builder().runId(77L).jobType(CollectJobType.DAILY).triggerType(TriggerType.SCHEDULER).status(CollectStatus.SUCCESS)
        .metadataJson(Map.of("steps", steps)).build();
  }

  private static Map<String, Object> step(String name, String status) {
    return Map.of("step", name, "status", status);
  }
}
