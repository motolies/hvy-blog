package kr.hvy.blog.modules.advisor.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;

/**
 * 장기 규칙 추천의 발행 주기 판정(M8). 스케줄러가 run 을 만들기 전에 이 판정으로 조용히 돌아간다(ADVISE 의 waitQuietly 와 같은 방식) —
 * cron 은 후보일(금요일·평일)마다 깨어나고 주기는 코드가 정한다. 수동 트리거(API·관리자 화면)는 주기와 무관하게 돈다.
 * <ul>
 *   <li>H60: 금요일이면서 ISO 주차(week-based-year)가 짝수. 53주가 있는 해는 52 → 2 주차 사이가 3주가 된다(허용 — 주차 기준이라 재현이 쉽다)</li>
 *   <li>H180: 그 달의 첫 거래일 = 개장일이면서 직전 개장일이 다른 달. 2026-10-16 부터는 첫 거래일~N번째 거래일 창 안에서
 *   그 달 LIVE H180 판단이 없으면 발행(따라잡기 — cron 이 한 번뿐이라 첫 거래일 실패·미배포 시 한 달을 통째로 놓치던 문제,
 *   {@code AdvisorGateService.h180Due})</li>
 * </ul>
 * 금요일이 휴장인 짝수 주는 H60 을 건너뛴다(잡 게이트가 SKIPPED) — 다음 영업일로 옮기지 않아 표본 간격이 고정된다.
 */
public final class LongTermCadence {

  private LongTermCadence() {
  }

  /**
   * H60 발행일(격주 금요일)인지.
   */
  public static boolean isH60Day(LocalDate date) {
    return date.getDayOfWeek() == DayOfWeek.FRIDAY && date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0;
  }

  /**
   * 그 달의 몇 번째 거래일인지(1부터). 휴장일이면 0. 1일부터 date 까지 개장일을 센다.
   */
  public static int tradingDayOfMonth(LocalDate date, MarketCalendarService calendar) {
    return countTradingDays(date, calendar, Integer.MAX_VALUE);
  }

  /**
   * 그 달 첫 거래일부터 window 번째 거래일 안의 개장일인지. isTradingDay 가 호출마다 DB 를 보므로 window 를 넘으면 바로 멈춘다.
   */
  public static boolean isWithinFirstTradingDays(LocalDate date, MarketCalendarService calendar, int window) {
    int n = countTradingDays(date, calendar, window + 1);
    return n >= 1 && n <= window;
  }

  /**
   * 1일부터 date 까지 개장일 수(limit 에서 멈춤). date 가 휴장이면 0.
   */
  private static int countTradingDays(LocalDate date, MarketCalendarService calendar, int limit) {
    if (!calendar.isTradingDay(date)) {
      return 0;
    }
    int count = 0;
    for (LocalDate d = date.withDayOfMonth(1); !d.isAfter(date) && count < limit; d = d.plusDays(1)) {
      if (d.equals(date) || calendar.isTradingDay(d)) {
        count++;
      }
    }
    return count;
  }

  /**
   * H180 발행일(그 달 첫 거래일)인지.
   */
  public static boolean isFirstTradingDayOfMonth(LocalDate date, MarketCalendarService calendar) {
    if (!calendar.isTradingDay(date)) {
      return false;
    }
    LocalDate previous = calendar.lastTradingDayOnOrBefore(date.minusDays(1));
    return !YearMonth.from(previous).equals(YearMonth.from(date));
  }
}
