package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * advisor run 상태. SKIPPED 는 게이트(휴장일·DAILY 미완료)로 본문을 돌리지 않고 닫은 run, CANCELED 는 관리자가 POST /runs/{id}/cancel 로 닫은 run
 * (잡은 다음 단계·IC 청크 경계에서 감지해 스스로 멈춘다, 2026-09-13).
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum AdvisorStatus implements EnumCode<String> {
  RUNNING("RUNNING", "실행 중"),
  SUCCESS("SUCCESS", "성공"),
  PARTIAL("PARTIAL", "부분 성공(가드 제거율 초과 등)"),
  FAILED("FAILED", "실패"),
  SKIPPED("SKIPPED", "조건 미충족으로 건너뜀"),
  CANCELED("CANCELED", "관리자 취소");

  private final String code;
  private final String desc;
}
