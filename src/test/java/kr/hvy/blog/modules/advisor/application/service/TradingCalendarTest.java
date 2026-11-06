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
  @DisplayName("이전 영업일 n개: asOf 제외·최근 순, 주말·휴장일을 건너뛰고 마지막 원소가 창 시작일")
  void previousTradingDays() {
    // 2026-09-18(금) 기준 앞 4 영업일: 17(목) 16(수) 14(월) 11(금) — 15(화) 휴장·주말 제외
    assertThat(tradingCalendar.previousTradingDays(LocalDate.of(2026, 9, 18), 4))
        .containsExactly(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 11));
    assertThat(tradingCalendar.previousTradingDays(LocalDate.of(2026, 9, 14), 1)).containsExactly(LocalDate.of(2026, 9, 11));
  }

  @Test
  @DisplayName("180거래일 구간은 연도를 넘어 계산한다")
  void longWindows() {
    LocalDate base = LocalDate.of(2026, 11, 5);
    assertThat(tradingCalendar.nextTradingDays(base, 180))
        .hasSize(180).isSorted().doesNotHaveDuplicates()
        .allMatch(d -> d.isAfter(base) && calendar.isTradingDay(d));
    assertThat(tradingCalendar.window(base, 180))
        .isEqualTo(new TradingCalendar.Window(LocalDate.of(2026, 11, 6), LocalDate.of(2027, 7, 15)));
    assertThat(tradingCalendar.previousTradingDays(base, 180))
        .hasSize(180).isSortedAccordingTo(java.util.Comparator.reverseOrder()).doesNotHaveDuplicates()
        .startsWith(LocalDate.of(2026, 11, 4)).endsWith(LocalDate.of(2026, 2, 25))
        .allMatch(d -> d.isBefore(base) && calendar.isTradingDay(d));
  }

  @Test
  @DisplayName("120일을 넘는 구간도 전체 거래일을 계산한다")
  void countsEntireLongInterval() {
    assertThat(tradingCalendar.tradingDaysBetween(LocalDate.of(2026, 11, 5), LocalDate.of(2027, 7, 15)))
        .isEqualTo(180);
  }

  @Test
  @DisplayName("장기 탐색도 거래일이 없으면 계산된 상한에서 실패한다")
  void longSearchRemainsBounded() {
    when(calendar.isTradingDay(any())).thenReturn(false);
    LocalDate base = LocalDate.of(2026, 11, 5);
    assertThatThrownBy(() -> tradingCalendar.nextTradingDays(base, 180))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("360일");
    assertThatThrownBy(() -> tradingCalendar.previousTradingDays(base, 180))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("360일");
  }

  @Test
  @DisplayName("영업일이 전혀 없으면(캘린더 오류) 예외로 드러낸다")
  void failsWhenNoTradingDays() {
    when(calendar.isTradingDay(any())).thenReturn(false);
    assertThatThrownBy(() -> tradingCalendar.window(LocalDate.of(2026, 9, 11), 5)).isInstanceOf(IllegalStateException.class);
  }
}
