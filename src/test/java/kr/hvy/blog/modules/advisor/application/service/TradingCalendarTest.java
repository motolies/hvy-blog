package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * 적용 구간(D+1 ~ D+h 영업일) 예정 계산: 주말·휴장일을 건너뛴다.
 */
class TradingCalendarTest {

  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final TradingCalendar tradingCalendar = new TradingCalendar(calendar);
  /** 2026-09-15(화) 휴장 가정 */
  private final Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 9, 15));

  TradingCalendarTest() {
    when(calendar.isTradingDay(any())).thenAnswer(inv -> {
      LocalDate d = inv.getArgument(0);
      // 재스터빙(when) 호출 때 null 인자로 한 번 실행되므로 null 안전
      return d != null && d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    });
  }

  @Test
  @DisplayName("금요일 판단의 진입은 월요일, 5번째 영업일은 휴장일을 건너뛴 다음 주 월요일")
  void windowSkipsWeekendAndHoliday() {
    TradingCalendar.Window w = tradingCalendar.window(LocalDate.of(2026, 9, 11), 5);
    assertThat(w.entry()).isEqualTo(LocalDate.of(2026, 9, 14));
    assertThat(w.exit()).as("14(월) 16(수) 17(목) 18(금) 21(월)").isEqualTo(LocalDate.of(2026, 9, 21));
    assertThat(tradingCalendar.nextTradingDays(LocalDate.of(2026, 9, 11), 2)).containsExactly(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));
  }

  @Test
  @DisplayName("영업일 차: (from, to] 의 개장일 수, 역순·같은 날은 0")
  void tradingDaysBetween() {
    assertThat(tradingCalendar.tradingDaysBetween(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11))).isEqualTo(1);
    assertThat(tradingCalendar.tradingDaysBetween(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 16))).as("14(월) 16(수)").isEqualTo(2);
    assertThat(tradingCalendar.tradingDaysBetween(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11))).isZero();
    assertThat(tradingCalendar.tradingDaysBetween(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 11))).isZero();
    assertThat(tradingCalendar.tradingDaysBetween(null, LocalDate.of(2026, 9, 11))).isZero();
  }

  @Test
  @DisplayName("영업일이 전혀 없으면(캘린더 오류) 예외로 드러낸다")
  void failsWhenNoTradingDays() {
    when(calendar.isTradingDay(any())).thenReturn(false);
    assertThatThrownBy(() -> tradingCalendar.window(LocalDate.of(2026, 9, 11), 5)).isInstanceOf(IllegalStateException.class);
  }
}
