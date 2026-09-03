package kr.hvy.blog.modules.stock.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisHolidayResponse;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.model.HolidayRow;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketHolidayWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 영업일 캘린더(HOLIDAY 잡 + 판정). 미래 판정은 tb_stock_market_holiday, 과거 정본은 KOSPI 지수 일봉 날짜 집합이다.
 * 휴장일 API 는 원장 연동이라 하루 1회 호출이 권고되므로 스케줄러는 1페이지, 백필 트리거는 최대 페이지를 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketCalendarService implements CollectJob {

  private final KisMarketDataPort marketDataPort;
  private final MarketHolidayWriter holidayWriter;
  private final KisProperties properties;

  @Override
  public CollectJobType jobType() {
    return CollectJobType.HOLIDAY;
  }

  /**
   * 오늘 기준 1페이지(약 1개월)를 받는다. startDate 를 지정하면 백필 의도로 보고 그 날부터 최대 페이지를 받는다.
   * 휴장일 API 는 원장 연동이라 평소에는 하루 1호출로 제한한다.
   */
  @Override
  public void execute(CollectExecution execution) {
    LocalDate requested = execution.request().startDate();
    LocalDate base = Optional.ofNullable(requested).orElse(MarketClock.today());
    int pages = requested == null ? 1 : properties.getCalendar().getMaxPages();
    int rows = collect(execution, base, pages);
    execution.addRows(rows);
    execution.targetDone();
  }

  /**
   * 기준일부터 maxPages 페이지를 받아 저장하고 변경 행 수를 돌려준다.
   */
  public int collect(CollectExecution execution, LocalDate base, int maxPages) {
    List<KisHolidayResponse.Day> days = marketDataPort.fetchHolidays(base, maxPages, execution.context("HOLIDAY"));
    List<HolidayRow> rows = StockRowMapper.toHolidayRows(days);
    int changed = holidayWriter.upsert(rows);
    LocalDate min = rows.stream().map(HolidayRow::tradeDate).min(LocalDate::compareTo).orElse(null);
    LocalDate max = rows.stream().map(HolidayRow::tradeDate).max(LocalDate::compareTo).orElse(null);
    execution.putMetadata("holidayFrom", min == null ? null : min.toString());
    execution.putMetadata("holidayTo", max == null ? null : max.toString());
    execution.putMetadata("holidayDays", rows.size());
    log.info("휴장일 수집: base={}, days={}, changed={}, range={}~{}", base, rows.size(), changed, min, max);
    return changed;
  }

  /**
   * 개장일 여부. 캘린더에 없는 날짜는 평일이면 개장으로 본다(보수적 기본값 — 잡을 건너뛰는 쪽이 더 위험).
   */
  public boolean isTradingDay(LocalDate date) {
    return holidayWriter.findOpen(date).orElseGet(() -> isWeekday(date));
  }

  /**
   * 캘린더에 해당 일자가 수집되어 있는지.
   */
  public boolean isKnown(LocalDate date) {
    return holidayWriter.findOpen(date).isPresent();
  }

  /**
   * date 이하의 가장 최근 개장일. 캘린더 범위를 벗어나면 주말만 피한다.
   */
  public LocalDate lastTradingDayOnOrBefore(LocalDate date) {
    LocalDate cursor = date;
    for (int i = 0; i < 15; i++) {
      if (isTradingDay(cursor)) {
        return cursor;
      }
      cursor = cursor.minusDays(1);
    }
    return cursor;
  }

  static boolean isWeekday(LocalDate date) {
    DayOfWeek day = date.getDayOfWeek();
    return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
  }
}
