package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 종목 픽 후보 유니버스(advisor.pick-universe, advice-v7). 스크리닝 백분위를 매긴 <b>뒤</b> 후보에만 거는 필터라 IC·가중치 학습 유니버스(advisor.markets)와 무관하다.
 * <p>
 * code 는 상수명과 같다 — yml·REST 파라미터(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum PickUniverse implements EnumCode<String> {
  /** 기준일 시점(PIT) KOSPI200 구성종목만 — tb_stock_master_history 의 그날 유효 행 */
  KOSPI200("KOSPI200", "KOSPI200 구성종목"),
  /** advisor.markets 유니버스 전체 (QUANT_TOPN_BROAD 섀도의 유니버스) */
  ALL("ALL", "시장 유니버스 전체");

  private final String code;
  private final String desc;
}
