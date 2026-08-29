package kr.hvy.blog.modules.stats.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 사이트별 핫딜 수집 현황.
 * <p>
 * 총합이 아니라 <b>사이트별로 분해</b>하는 것이 핵심이다.
 * {@code HotDealService.scrapeAndNotify()} 는 사이트별 예외를 삼키고 다음 사이트로 넘어가므로,
 * 총 수집 건수만 보면 한 사이트의 스크래퍼가 깨져도 나머지가 숫자를 채워 가려진다.
 * lastScrapedAt 을 사이트별로 보면 깨진 사이트만 시각이 멈춰 즉시 드러난다.
 */
@Value
@Builder
@Jacksonized
public class HotDealSiteStat {

  /**
   * 게시판 행의 PK. <b>siteCode 는 스크래퍼 종류라 고유하지 않다</b> —
   * 뽐뿌는 국내/해외 게시판이 같은 PPOMPPU 코드를 공유한다(db/hotdeal-schema.sql 시드 참조).
   * 목록 렌더링의 행 식별자는 반드시 이 값을 써야 한다.
   */
  Long siteId;
  String siteCode;
  String siteName;
  boolean enabled;
  long scrapedCount;
  long notifiedCount;
  /** 집계 창 밖이어도 반환한다 — 수집이 멈춘 사이트의 마지막 시각이야말로 알아야 할 값이다. */
  Instant lastScrapedAt;
}
