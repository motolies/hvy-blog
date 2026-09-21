package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 12:00 픽 편차의 결정론 분류 (note-v1, 2026-09-21). z = 지수 대비 초과 / 반나절 σ, AVOID 는 부호를 뒤집어 같은 규칙을 쓴다(PickDeviation.classify).
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum PickNoteClass implements EnumCode<String> {
  FLAT("FLAT", "편차 미미 (|z| < 임계이고 픽 자체 등락도 −임계σ 안, 회고 생략)"),
  ON_TRACK("ON_TRACK", "방향 일치 (임계 ≤ z < 2×임계)"),
  MARKET_DRAG("MARKET_DRAG", "시장 동조 하락 (지수와 같이 −임계σ 이상 빠졌거나, 하락분의 절반 이상이 지수로 설명)"),
  IDIOSYNCRATIC("IDIOSYNCRATIC", "종목 고유 이탈 (지수 대비 −임계σ 이상 뒤처짐)"),
  OVERSHOOT("OVERSHOOT", "방향 일치·과속 (z ≥ 2×임계, 되돌림 경고)");

  private final String code;
  private final String desc;
}
