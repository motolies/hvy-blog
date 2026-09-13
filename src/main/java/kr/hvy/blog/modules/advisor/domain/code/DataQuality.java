package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 판단 입력 품질. DEGRADED 는 DAILY 단계 결손일이라 학습에서 제외한다.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum DataQuality implements EnumCode<String> {
  OK("OK", "정상"),
  DEGRADED("DEGRADED", "수집 결손");

  private final String code;
  private final String desc;
}
