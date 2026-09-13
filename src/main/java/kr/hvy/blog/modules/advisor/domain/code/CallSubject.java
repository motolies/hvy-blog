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
  SECTOR("SECTOR", "주도 섹터"),
  /** 추세 지속 기간 버킷 (h=20 진단 패스, Brier 는 적중 기준 — INDEX 의 부호 기준과 섞지 않는다) */
  TREND("TREND", "추세 지속"),
  /** 무효화 조건의 조기 신호 적중 (혼동행렬: 전환·발동 일치가 적중) */
  TREND_INV("TREND_INV", "추세 무효화"),
  /** 아침 점검 예상 갭(β × 미국 밤사이 수익률) vs D+1 시가 갭 (h=1) */
  MORNING("MORNING", "아침 갭 판정");

  private final String code;
  private final String desc;
}
