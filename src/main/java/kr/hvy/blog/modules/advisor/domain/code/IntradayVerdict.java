package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 장중 점검 판정 — 지수 대비 앞선 픽 비율로 정한다(2026-10-16~, 그 전 행은 전일 대비 상승 픽 비율). 지수 방향 적중은 점검 comment·index_json 에 따로 있다.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum IntradayVerdict implements EnumCode<String> {
  ON_TRACK("ON_TRACK", "지수 대비 우위"),
  MIXED("MIXED", "혼조"),
  OFF_TRACK("OFF_TRACK", "지수 대비 열위");

  private final String code;
  private final String desc;
}
