package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장기 규칙 추천 주기(M8): H60 은 짝수 ISO 주 금요일, H180 은 그 달 첫 거래일.
 */
class LongTermCadenceTest {

  @Test
  @DisplayName("H60 격주: 금요일이면서 ISO 주차가 짝수일 때만 — 2026-10-02(40주)·10-16(42주)는 발행, 10-09(41주)·목요일·53주는 아님")
  void h60BiweeklyByIsoWeek() {
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2026, 10, 2))).isTrue();
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2026, 10, 9))).isFalse();
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2026, 10, 16))).isTrue();
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2026, 10, 15))).as("목요일").isFalse();
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2026, 12, 25))).as("52주").isTrue();
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2027, 1, 1))).as("53주(2026 week-based-year)").isFalse();
    assertThat(LongTermCadence.isH60Day(LocalDate.of(2027, 1, 8))).as("2027 1주").isFalse();
    // 1년 동안 발행 금요일은 26회 안팎 — 격주
    long count = LocalDate.of(2027, 1, 1).datesUntil(LocalDate.of(2028, 1, 1)).filter(LongTermCadence::isH60Day).count();
    assertThat(count).isBetween(25L, 27L);
  }

  @Test
  @DisplayName("H180 월간: 그 달 첫 거래일만 — 1일이 휴장·주말이면 다음 개장일, 둘째 거래일·휴장일은 아님")
  void h180FirstTradingDayOfMonth() {
    // 휴장: 주말 + 2026-10-01(목) 가상 휴장 → 10월 첫 거래일은 10-02(금)
    Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 10, 1));
    MarketCalendarService calendar = mock(MarketCalendarService.class);
    when(calendar.isTradingDay(any())).thenAnswer(inv -> open(inv.getArgument(0), holidays));
    when(calendar.lastTradingDayOnOrBefore(any())).thenAnswer(inv -> {
      LocalDate d = inv.getArgument(0);
      while (!open(d, holidays)) {
        d = d.minusDays(1);
      }
      return d;
    });

    assertThat(LongTermCadence.isFirstTradingDayOfMonth(LocalDate.of(2026, 10, 1), calendar)).as("휴장").isFalse();
    assertThat(LongTermCadence.isFirstTradingDayOfMonth(LocalDate.of(2026, 10, 2), calendar)).isTrue();
    assertThat(LongTermCadence.isFirstTradingDayOfMonth(LocalDate.of(2026, 10, 5), calendar)).as("둘째 거래일").isFalse();
    assertThat(LongTermCadence.isFirstTradingDayOfMonth(LocalDate.of(2026, 11, 2), calendar)).as("11-01 일요일 → 11-02 월").isTrue();
    assertThat(LongTermCadence.isFirstTradingDayOfMonth(LocalDate.of(2026, 9, 30), calendar)).as("월말").isFalse();
    long count = LocalDate.of(2026, 1, 1).datesUntil(LocalDate.of(2027, 1, 1)).filter(d -> LongTermCadence.isFirstTradingDayOfMonth(d, calendar)).count();
    assertThat(count).as("1년 12회").isEqualTo(12);
  }

  private static boolean open(LocalDate d, Set<LocalDate> holidays) {
    return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
  }
}
