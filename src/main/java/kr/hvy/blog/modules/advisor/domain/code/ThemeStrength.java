package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * KOSPI200 섹터 대분류(테마) 강약 라벨(M6). 구성 종목 중앙값 수익률의 동일가중 벤치(KOSPI200 전 구성 종목 중앙값) 대비 초과(rsEq)로 규칙이 정한다 —
 * rsEq20 ≥ advisor.theme.strong-rs20 이고 rsEq60 &gt; 0 이면 STRONG, rsEq20 ≤ −strong-rs20 이고 rsEq60 &lt; 0 이면 WEAK, 그 외 NEUTRAL.
 * 2026-10-16(advice-v11) 전 저장분(regime_json theme basis 없음)은 KOSPI 종합 대비 rs20/rs60 기준이다.
 */
@Getter
@AllArgsConstructor
public enum ThemeStrength implements EnumCode<String> {
  STRONG("STRONG", "강세"),
  NEUTRAL("NEUTRAL", "중립"),
  WEAK("WEAK", "약세");

  private final String code;
  private final String desc;
}
