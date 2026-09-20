package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 거시 지표 원천 (tb_stock_macro_daily.source). 라이브 원천과 백필 원천이 같아야 게시 지연 차이가 룩어헤드가 되지 않으므로
 * 시리즈당 원천은 하나다. 키가 필요 없는 공개 CSV 만 둔다.
 */
@Getter
@AllArgsConstructor
public enum MacroSource implements EnumCode<String> {
  CBOE("CBOE", "CBOE 일별 지수 CSV (마감 후 당일 갱신)"),
  TREASURY("TREASURY", "미국 재무부 일별 국채 수익률 CSV (연도별 파일)");

  private final String code;
  private final String desc;
}
