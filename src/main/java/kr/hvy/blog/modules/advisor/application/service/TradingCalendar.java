package kr.hvy.blog.modules.advisor.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판단 적용 구간(진입 D+1 시가 ~ 청산 D+h 종가)의 "예정" 영업일 계산. 미래 판정은 MarketCalendarService(휴장일 테이블, 미수집 날짜는 평일=개장)라
 * 표시는 예정이며, 실제 진입·청산일은 채점 시 vw_stock_market_calendar(과거 정본) 로 재확정된다 — AdviceScoringService 의 cal CTE 와 같은 규약.
 */
@Component
@RequiredArgsConstructor
public class TradingCalendar {

  /** 영업일 탐색 상한(캘린더일). 연휴·연말을 넉넉히 덮는다 */
  private static final int MAX_SCAN_DAYS = 120;

  private final MarketCalendarService calendar;

  /** 적용 구간 */
  public record Window(LocalDate entry, LocalDate exit) {
  }

  /**
   * asOf 다음 영업일부터 h번째 영업일까지의 구간.
   */
  public Window window(LocalDate asOf, int horizonDays) {
    List<LocalDate> days = nextTradingDays(asOf, horizonDays);
    return new Window(days.getFirst(), days.getLast());
  }

  /**
   * asOf 뒤의 영업일 n개(asOf 제외, 오름차순).
   */
  public List<LocalDate> nextTradingDays(LocalDate asOf, int n) {
    List<LocalDate> days = new ArrayList<>();
    LocalDate cursor = asOf;
    for (int i = 0; i < MAX_SCAN_DAYS && days.size() < n; i++) {
      cursor = cursor.plusDays(1);
      if (calendar.isTradingDay(cursor)) {
        days.add(cursor);
      }
    }
    if (days.size() < n) {
      throw new IllegalStateException("영업일 " + n + "개를 " + MAX_SCAN_DAYS + "일 안에 찾지 못했습니다: asOf=" + asOf);
    }
    return days;
  }

  /**
   * (from, to] 사이의 영업일 수. from ≥ to 면 0.
   */
  public int tradingDaysBetween(LocalDate from, LocalDate to) {
    if (from == null || to == null || !from.isBefore(to)) {
      return 0;
    }
    int count = 0;
    LocalDate cursor = from;
    for (int i = 0; i < MAX_SCAN_DAYS && cursor.isBefore(to); i++) {
      cursor = cursor.plusDays(1);
      if (calendar.isTradingDay(cursor)) {
        count++;
      }
    }
    return count;
  }
}
