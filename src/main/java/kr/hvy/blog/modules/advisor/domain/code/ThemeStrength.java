package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * KOSPI200 섹터 대분류(테마) 강약 라벨(M6). 구성 종목 중앙값 수익률의 KOSPI 대비 초과(rs)로 규칙이 정한다 —
 * rs20 ≥ advisor.theme.strong-rs20 이고 rs60 &gt; 0 이면 STRONG, rs20 ≤ −strong-rs20 이고 rs60 &lt; 0 이면 WEAK, 그 외 NEUTRAL.
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
