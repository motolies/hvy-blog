package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 국내 시장 기준 시각. DB·JVM 은 UTC 지만 거래일과 스케줄 판정은 KST 로 한다.
 */
public final class MarketClock {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private MarketClock() {
  }

  /**
   * 오늘 날짜(KST).
   */
  public static LocalDate today() {
    return LocalDate.now(KST);
  }

  /**
   * 현재 시각(KST).
   */
  public static ZonedDateTime now() {
    return ZonedDateTime.now(KST);
  }
}
