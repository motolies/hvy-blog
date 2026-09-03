package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 기업행사 유형. adjustsPrice 가 true 인 유형만 수정주가 계수 산출 대상이다.
 * 배당은 KRX 수정주가 관례상 보정하지 않고 총수익 계산용으로만 보관한다.
 */
@Getter
@AllArgsConstructor
public enum CorporateActionType implements EnumCode<String> {
  SPLIT("SPLIT", "액면분할", true),
  REVERSE_SPLIT("REVERSE_SPLIT", "액면병합", true),
  BONUS_ISSUE("BONUS_ISSUE", "무상증자", true),
  RIGHTS_ISSUE("RIGHTS_ISSUE", "유상증자", true),
  CAPITAL_REDUCTION("CAPITAL_REDUCTION", "감자", true),
  MERGER_SPLIT("MERGER_SPLIT", "합병/분할", false),
  LISTING("LISTING", "상장/추가상장", false),
  DIVIDEND("DIVIDEND", "배당", false),
  CHART_HINT("CHART_HINT", "일봉 힌트(락·수정주가 플래그)", false);

  private final String code;
  private final String desc;
  private final boolean adjustsPrice;
}
