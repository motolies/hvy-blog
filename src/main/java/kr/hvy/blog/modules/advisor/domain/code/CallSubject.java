package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 국면·섹터 콜 채점 대상 종류.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum CallSubject implements EnumCode<String> {
  INDEX("INDEX", "지수 방향"),
  SECTOR("SECTOR", "주도 섹터");

  private final String code;
  private final String desc;
}
