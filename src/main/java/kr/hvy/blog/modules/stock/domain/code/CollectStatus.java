package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 수집 run 상태. PARTIAL 은 일부 종목만 실패해 임계(1%) 아래로 끝난 경우다.
 */
@Getter
@AllArgsConstructor
public enum CollectStatus implements EnumCode<String> {
  RUNNING("RUNNING", "실행 중"),
  SUCCESS("SUCCESS", "성공"),
  PARTIAL("PARTIAL", "부분 성공"),
  FAILED("FAILED", "실패"),
  CANCELED("CANCELED", "취소");

  private final String code;
  private final String desc;

  /**
   * 종료 상태인지 판정한다.
   */
  public boolean isTerminal() {
    return this != RUNNING;
  }
}
