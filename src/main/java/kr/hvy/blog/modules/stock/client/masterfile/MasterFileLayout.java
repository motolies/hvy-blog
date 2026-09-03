package kr.hvy.blog.modules.stock.client.masterfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.Getter;

/**
 * KIS 종목 마스터 파일(kospi_code.mst / kosdaq_code.mst)의 고정폭 레이아웃.
 * <p>
 * 한 줄은 [단축코드 9][표준코드 12][한글명 가변] + [고정폭 꼬리] 구조다. 한글명이 가변이라 앞에서부터 자를 수 없고,
 * 줄 끝에서 꼬리 길이(KOSPI 227자, KOSDAQ 221자)만큼 잘라낸 뒤 나머지를 머리로 본다.
 * 필드 순서·폭은 공식 헤더(종목마스터정보(코스피).h, 종목마스터정보(코스닥).h)와 공식 파서
 * (stocks_info/kis_kospi_code_mst.py, kis_kosdaq_code_mst.py)를 따른다. 두 시장의 꼬리 구조는 '기준가' 이후가 같고
 * 앞부분 플래그 구성만 다르므로, 필드는 이름으로 접근하고 없는 필드는 null 로 둔다.
 */
@Getter
public enum MasterFileLayout implements EnumCode<String> {

  KOSPI("KOSPI", "코스피 종목 마스터", "kospi_code", MarketType.KOSPI, kospiSpecs()),
  KOSDAQ("KOSDAQ", "코스닥 종목 마스터", "kosdaq_code", MarketType.KOSDAQ, kosdaqSpecs());

  /** 머리 부분: 단축코드 9자 + 표준코드 12자 */
  public static final int TICKER_WIDTH = 9;
  public static final int STANDARD_CODE_WIDTH = 12;
  public static final int HEAD_FIXED_WIDTH = TICKER_WIDTH + STANDARD_CODE_WIDTH;

  // ---- 필드 이름 (양 시장 공통) ----
  public static final String GROUP_CODE = "groupCode";
  public static final String CAP_SCALE = "capScale";
  public static final String SECTOR_LARGE = "sectorLarge";
  public static final String SECTOR_MID = "sectorMid";
  public static final String SECTOR_SMALL = "sectorSmall";
  public static final String KOSPI200_SECTOR = "kospi200Sector";
  public static final String KOSDAQ150 = "kosdaq150";
  public static final String BASE_PRICE = "basePrice";
  public static final String SUSPENDED = "suspended";
  public static final String LIQUIDATING = "liquidating";
  public static final String ADMINISTRATIVE = "administrative";
  public static final String MARKET_ALARM = "marketAlarm";
  public static final String EX_RIGHTS_CODE = "exRightsCode";
  public static final String PAR_VALUE_CHANGE_CODE = "parValueChangeCode";
  public static final String CAPITAL_INCREASE_CODE = "capitalIncreaseCode";
  public static final String PREV_VOLUME = "prevVolume";
  public static final String PAR_VALUE = "parValue";
  public static final String LISTING_DATE = "listingDate";
  public static final String LISTED_SHARES = "listedShares";
  public static final String CAPITAL = "capital";
  public static final String SETTLE_MONTH = "settleMonth";
  public static final String PREFERRED_CODE = "preferredCode";
  public static final String KRX300 = "krx300";
  public static final String KOSPI_FLAG = "kospi";
  public static final String MARKET_CAP = "marketCap";
  public static final String BASE_YEAR_MONTH = "baseYearMonth";

  private final String code;
  private final String desc;
  /** 다운로드 파일 이름 (확장자 .mst.zip 제외) */
  private final String fileName;
  private final MarketType marketType;
  private final List<FieldSpec> specs;
  /** 꼬리 전체 길이 = 폭 합계 */
  private final int tailWidth;
  private final Map<String, int[]> offsets;

  MasterFileLayout(String code, String desc, String fileName, MarketType marketType, List<FieldSpec> specs) {
    this.code = code;
    this.desc = desc;
    this.fileName = fileName;
    this.marketType = marketType;
    this.specs = Collections.unmodifiableList(specs);
    Map<String, int[]> map = new LinkedHashMap<>();
    int cursor = 0;
    for (FieldSpec spec : specs) {
      map.put(spec.name(), new int[]{cursor, cursor + spec.width()});
      cursor += spec.width();
    }
    this.tailWidth = cursor;
    this.offsets = Collections.unmodifiableMap(map);
  }

  /**
   * 꼬리 문자열에서 필드 값을 잘라낸다(trim). 이 레이아웃에 없는 필드면 null.
   */
  public String extract(String tail, String field) {
    int[] range = offsets.get(field);
    if (range == null || tail == null || tail.length() < range[1]) {
      return null;
    }
    return tail.substring(range[0], range[1]).trim();
  }

  /**
   * 이 레이아웃이 필드를 가지는지.
   */
  public boolean has(String field) {
    return offsets.containsKey(field);
  }

  /** 필드 1개: 이름과 폭 */
  public record FieldSpec(String name, int width) {
  }

  private static FieldSpec f(String name, int width) {
    return new FieldSpec(name, width);
  }

  /**
   * '기준가'부터 끝까지 두 시장에 공통인 꼬리 뒷부분.
   */
  private static List<FieldSpec> commonTail(boolean withKospiFlag) {
    List<FieldSpec> list = new ArrayList<>(List.of(
        f(BASE_PRICE, 9), f("tradeUnit", 5), f("afterHoursTradeUnit", 5),
        f(SUSPENDED, 1), f(LIQUIDATING, 1), f(ADMINISTRATIVE, 1),
        f(MARKET_ALARM, 2), f("marketAlarmNotice", 1), f("unfaithfulDisclosure", 1), f("backdoorListing", 1),
        f(EX_RIGHTS_CODE, 2), f(PAR_VALUE_CHANGE_CODE, 2), f(CAPITAL_INCREASE_CODE, 2), f("marginRate", 3),
        f("creditAvailable", 1), f("creditDays", 3),
        f(PREV_VOLUME, 12), f(PAR_VALUE, 12), f(LISTING_DATE, 8), f(LISTED_SHARES, 15), f(CAPITAL, 21),
        f(SETTLE_MONTH, 2), f("ipoPrice", 7), f(PREFERRED_CODE, 1),
        f("shortSaleOverheat", 1), f("abnormalSurge", 1), f(KRX300, 1)));
    if (withKospiFlag) {
      list.add(f(KOSPI_FLAG, 1));
    }
    list.addAll(List.of(
        f("revenue", 9), f("operatingProfit", 9), f("ordinaryProfit", 9), f("netIncome", 5), f("roe", 9),
        f(BASE_YEAR_MONTH, 8), f(MARKET_CAP, 9), f("groupCompanyCode", 3),
        f("creditLimitOver", 1), f("collateralLoanable", 1), f("stockLoanable", 1)));
    return list;
  }

  /**
   * 코스피 꼬리 227자 70필드 (ST_KSP_CODE).
   */
  private static List<FieldSpec> kospiSpecs() {
    List<FieldSpec> list = new ArrayList<>(List.of(
        f(GROUP_CODE, 2), f(CAP_SCALE, 1), f(SECTOR_LARGE, 4), f(SECTOR_MID, 4), f(SECTOR_SMALL, 4),
        f("manufacturing", 1), f("lowLiquidity", 1), f("governanceIndex", 1), f(KOSPI200_SECTOR, 1), f("kospi100", 1),
        f("kospi50", 1), f("krx", 1), f("etpType", 1), f("elwIssued", 1), f("krx100", 1),
        f("krxAuto", 1), f("krxSemicon", 1), f("krxBio", 1), f("krxBank", 1), f("spac", 1),
        f("krxEnergyChem", 1), f("krxSteel", 1), f("shortOverheat", 1), f("krxMediaTelecom", 1), f("krxConstruction", 1),
        f("krxFinancialServiceRemoved", 1), f("krxSecurities", 1), f("krxShip", 1), f("krxInsurance", 1), f("krxTransport", 1),
        f("sri", 1)));
    list.addAll(commonTail(true));
    return list;
  }

  /**
   * 코스닥 꼬리 221자 64필드 (ST_KSQ_CODE).
   */
  private static List<FieldSpec> kosdaqSpecs() {
    List<FieldSpec> list = new ArrayList<>(List.of(
        f(GROUP_CODE, 2), f(CAP_SCALE, 1), f(SECTOR_LARGE, 4), f(SECTOR_MID, 4), f(SECTOR_SMALL, 4),
        f("venture", 1), f("lowLiquidity", 1), f("krx", 1), f("etpType", 1), f("krx100", 1),
        f("krxAuto", 1), f("krxSemicon", 1), f("krxBio", 1), f("krxBank", 1), f("spac", 1),
        f("krxEnergyChem", 1), f("krxSteel", 1), f("shortOverheat", 1), f("krxMediaTelecom", 1), f("krxConstruction", 1),
        f("investmentCaution", 1), f("krxSecurities", 1), f("krxShip", 1), f("krxInsurance", 1), f("krxTransport", 1),
        f(KOSDAQ150, 1)));
    list.addAll(commonTail(false));
    return list;
  }
}
