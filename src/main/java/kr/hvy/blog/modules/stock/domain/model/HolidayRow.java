package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;

/**
 * tb_stock_market_holiday 1행. 개장일(isOpen)이 "주문·시세가 있는 날"의 기준이다.
 */
public record HolidayRow(LocalDate tradeDate, boolean isOpen, boolean isBusiness, boolean isTrading, boolean isSettlement) {
}
