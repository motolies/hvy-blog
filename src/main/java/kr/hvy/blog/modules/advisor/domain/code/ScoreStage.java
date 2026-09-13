package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 채점 단계. 유상증자 계수가 최대 6일 늦게 반영되므로 잠정 → 다음 WEEKLY 뒤 확정으로 두 번 계산한다.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum ScoreStage implements EnumCode<String> {
  PROVISIONAL("PROVISIONAL", "잠정(T+h 직후)"),
  CONFIRMED("CONFIRMED", "확정(이후 첫 WEEKLY 뒤 재계산)");

  private final String code;
  private final String desc;
}
