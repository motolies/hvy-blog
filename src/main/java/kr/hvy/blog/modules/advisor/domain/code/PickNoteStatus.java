package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 픽 노트 상태. 12:00 관측은 OPEN 으로 저장되고, T+5 채점이 저장되는 순간 12:00 초과 부호와 T+5 초과 부호가 같으면 CONFIRMED, 다르면 REFUTED 로 확정된다
 * (append-only — 확정 뒤에는 바뀌지 않는다).
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum PickNoteStatus implements EnumCode<String> {
  OPEN("OPEN", "미확정 (T+5 채점 전)"),
  CONFIRMED("CONFIRMED", "확정 — 12:00 초과 부호가 T+5 와 일치"),
  REFUTED("REFUTED", "기각 — 12:00 초과 부호가 T+5 와 불일치");

  private final String code;
  private final String desc;
}
