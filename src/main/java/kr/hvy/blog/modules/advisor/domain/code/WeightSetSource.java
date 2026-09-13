package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 가중치 세트 출처.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum WeightSetSource implements EnumCode<String> {
  SEED("SEED", "설계 사전값"),
  BACKFILL("BACKFILL", "IC 사전 추정"),
  WEEKLY("WEEKLY", "주간 IC 갱신"),
  MANUAL("MANUAL", "수동 활성");

  private final String code;
  private final String desc;
}
