package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 교훈 범위.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum LessonScope implements EnumCode<String> {
  SIGNAL("SIGNAL", "시그널 버킷"),
  REGIME("REGIME", "시장 국면"),
  SECTOR("SECTOR", "섹터"),
  CALIBRATION("CALIBRATION", "신뢰도 보정");

  private final String code;
  private final String desc;
}
