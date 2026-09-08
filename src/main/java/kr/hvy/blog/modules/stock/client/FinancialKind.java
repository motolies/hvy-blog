package kr.hvy.blog.modules.stock.client;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 재무 API 6종. 파라미터 이름의 대소문자가 API 마다 섞여 있는데 공식 예제 그대로 쓴다.
 * (FID_DIV_CLS_CODE 0 년 / 1 분기, fid_cond_mrkt_div_code J, fid_input_iscd 종목)
 * <p>
 * <b>선언 순서 = 병합 순서</b>다. {@code FinancialRowMapper.merge} 가 이 순서로 putAll 하므로 나중 종류가 동명 필드를 덮는다.
 * 재무비율에도 있는 {@code grs}(매출액 증가율)·{@code lblt_rate}(부채비율)는 전문 API(성장성·안정성) 값이 남는다.
 */
@Getter
@AllArgsConstructor
public enum FinancialKind implements EnumCode<String> {
  INCOME_STATEMENT("INCOME_STATEMENT", "손익계산서", "/uapi/domestic-stock/v1/finance/income-statement", "FHKST66430200"),
  BALANCE_SHEET("BALANCE_SHEET", "대차대조표", "/uapi/domestic-stock/v1/finance/balance-sheet", "FHKST66430100"),
  FINANCIAL_RATIO("FINANCIAL_RATIO", "재무비율", "/uapi/domestic-stock/v1/finance/financial-ratio", "FHKST66430300"),
  GROWTH_RATIO("GROWTH_RATIO", "성장성비율", "/uapi/domestic-stock/v1/finance/growth-ratio", "FHKST66430800"),
  PROFIT_RATIO("PROFIT_RATIO", "수익성비율", "/uapi/domestic-stock/v1/finance/profit-ratio", "FHKST66430400"),
  STABILITY_RATIO("STABILITY_RATIO", "안정성비율", "/uapi/domestic-stock/v1/finance/stability-ratio", "FHKST66430600");

  private final String code;
  private final String desc;
  private final String path;
  private final String trId;
}
