package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 체크포인트 상태. DONE(목표 시작일 도달)과 EXHAUSTED(KIS 가 더 과거를 주지 않음)를 구분해
 * 소급 한계 실측 데이터로 남긴다.
 */
@Getter
@AllArgsConstructor
public enum CheckpointStatus implements EnumCode<String> {
  PENDING("PENDING", "대기"),
  IN_PROGRESS("IN_PROGRESS", "진행 중"),
  DONE("DONE", "완료"),
  EXHAUSTED("EXHAUSTED", "소급 한계 도달"),
  FAILED("FAILED", "실패");

  private final String code;
  private final String desc;
}
