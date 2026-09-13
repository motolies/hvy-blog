package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 중기 추세 국면(강세·보합·약세). {@link MarketRegimeCode}(5거래일 위험 선호)와는 다른 축이며, LLM 이 아니라 규칙(TrendSql)이 확정한다.
 * <p>
 * 성분 5개(종가/MA20, MA20/MA60, MA60/MA120, 60일 수익률, MA20 상회 종목 비율)의 합이 임계 이상이면 BULL, 이하면 BEAR, 그 외 SIDEWAYS.
 * 전환은 confirm-days 거래일 연속 같은 raw 라벨일 때만 반영된다(휩소 방지).
 * code 는 상수명과 같다 — DB 컬럼·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum MarketTrendCode implements EnumCode<String> {
  BULL("BULL", "강세"),
  SIDEWAYS("SIDEWAYS", "보합"),
  BEAR("BEAR", "약세");

  private final String code;
  private final String desc;
}
