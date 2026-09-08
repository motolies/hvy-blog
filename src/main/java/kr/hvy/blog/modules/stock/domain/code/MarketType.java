package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 국내 시장 구분. 마스터 파일 단위와 일치한다.
 */
@Getter
@AllArgsConstructor
public enum MarketType implements EnumCode<String> {
  KOSPI("KOSPI", "유가증권", "0001", "KSP"),
  KOSDAQ("KOSDAQ", "코스닥", "1001", "KSQ");

  private final String code;
  private final String desc;

  /** 해당 시장의 종합지수 코드 (FHKUP03500100 FID_INPUT_ISCD) */
  private final String compositeIndexCode;

  /** 시장별 투자자매매동향(FHPTJ04040000) FID_INPUT_ISCD_1 값 */
  private final String investorMarketCode;
}
