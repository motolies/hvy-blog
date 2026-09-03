package kr.hvy.blog.modules.stock.client;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 재무 API 핵심 3종. 파라미터 이름의 대소문자가 API 마다 섞여 있는데 공식 예제 그대로 쓴다.
 * (FID_DIV_CLS_CODE 0 년 / 1 분기, fid_cond_mrkt_div_code J, fid_input_iscd 종목)
 */
@Getter
@AllArgsConstructor
public enum FinancialKind implements EnumCode<String> {
  INCOME_STATEMENT("INCOME_STATEMENT", "손익계산서", "/uapi/domestic-stock/v1/finance/income-statement", "FHKST66430200"),
  BALANCE_SHEET("BALANCE_SHEET", "대차대조표", "/uapi/domestic-stock/v1/finance/balance-sheet", "FHKST66430100"),
  FINANCIAL_RATIO("FINANCIAL_RATIO", "재무비율", "/uapi/domestic-stock/v1/finance/financial-ratio", "FHKST66430300");

  private final String code;
  private final String desc;
  private final String path;
  private final String trId;
}
