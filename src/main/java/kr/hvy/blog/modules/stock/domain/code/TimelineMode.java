package kr.hvy.blog.modules.stock.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * GDELT DOC 2.0 API 시계열 모드. 기사량은 원 건수가 아니라 전체 모니터 대비 비율로만 쓴다(소스 커버리지 성장이 추세를 만들기 때문).
 */
@Getter
@AllArgsConstructor
public enum TimelineMode implements EnumCode<String> {
  VOLUME("VOLUME", "테마 기사량과 전체 모니터 기사량", "timelinevolraw"),
  TONE("TONE", "테마 평균 톤", "timelinetone");

  private final String code;
  private final String desc;

  /** API 의 mode 파라미터 값 */
  private final String gdeltMode;
}
