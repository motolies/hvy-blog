package kr.hvy.blog.modules.stock.client.masterfile;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.hvy.blog.modules.stock.client.KisValues;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import org.apache.commons.lang3.StringUtils;

/**
 * 마스터 파일 한 줄. 꼬리 원문을 들고 있다가 필드를 이름으로 꺼낸다.
 *
 * @param layout       시장별 레이아웃
 * @param ticker       단축코드 (6자리, ETN 은 Q 접두)
 * @param standardCode 표준코드 (ISIN)
 * @param name         한글 종목명
 * @param tail         고정폭 꼬리 원문
 */
public record MasterRecord(MasterFileLayout layout, String ticker, String standardCode, String name, String tail) {

  /** 증권그룹 주권 */
  public static final String GROUP_STOCK = "ST";

  /**
   * 필드 원문(trim). 레이아웃에 없으면 null.
   */
  public String get(String field) {
    return layout.extract(tail, field);
  }

  /**
   * Y/N 플래그.
   */
  public boolean flag(String field) {
    return KisValues.flag(get(field));
  }

  public Long longValue(String field) {
    return KisValues.longValue(get(field));
  }

  public BigDecimal decimal(String field) {
    return KisValues.decimal(get(field));
  }

  public LocalDate date(String field) {
    return KisValues.date(get(field));
  }

  public MarketType marketType() {
    return layout.getMarketType();
  }

  public String groupCode() {
    return StringUtils.defaultIfBlank(get(MasterFileLayout.GROUP_CODE), "");
  }

  public boolean isStock() {
    return GROUP_STOCK.equals(groupCode());
  }

  public String sectorLarge() {
    return blankToNull(get(MasterFileLayout.SECTOR_LARGE));
  }

  public String sectorMid() {
    return blankToNull(get(MasterFileLayout.SECTOR_MID));
  }

  public String sectorSmall() {
    return blankToNull(get(MasterFileLayout.SECTOR_SMALL));
  }

  /**
   * KOSPI200 섹터업종 코드. 0 은 해당 없음이므로 null 로 정규화한다. 코스닥은 필드가 없다.
   */
  public String kospi200Sector() {
    String value = get(MasterFileLayout.KOSPI200_SECTOR);
    return (value == null || value.isBlank() || "0".equals(value)) ? null : value;
  }

  public boolean isKospi200() {
    return kospi200Sector() != null;
  }

  public boolean isKrx300() {
    return flag(MasterFileLayout.KRX300);
  }

  public boolean isSuspended() {
    return flag(MasterFileLayout.SUSPENDED);
  }

  public boolean isLiquidating() {
    return flag(MasterFileLayout.LIQUIDATING);
  }

  public boolean isAdministrative() {
    return flag(MasterFileLayout.ADMINISTRATIVE);
  }

  public LocalDate listingDate() {
    return date(MasterFileLayout.LISTING_DATE);
  }

  public Long listedShares() {
    return longValue(MasterFileLayout.LISTED_SHARES);
  }

  public Long capital() {
    return longValue(MasterFileLayout.CAPITAL);
  }

  public BigDecimal parValue() {
    return decimal(MasterFileLayout.PAR_VALUE);
  }

  public String settleMonth() {
    return blankToNull(get(MasterFileLayout.SETTLE_MONTH));
  }

  private static String blankToNull(String value) {
    return StringUtils.isBlank(value) ? null : value;
  }
}
