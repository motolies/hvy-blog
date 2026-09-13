package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 시장 판단 잡 유형. run 테이블의 job_type 이며 RUNNING 부분 유니크 인덱스의 키다.
 * <p>
 * code 는 상수명과 같다 — DB 부분 인덱스(WHERE status='RUNNING')·REST 경로 변수(Enum.valueOf)가 상수명 기준이다.
 */
@Getter
@AllArgsConstructor
public enum AdvisorJobType implements EnumCode<String> {
  ADVISE("ADVISE", "일일 시장 판단·추천", true),
  SCORE("SCORE", "채점·IC 계산 (보충 실행)", true),
  INTRADAY("INTRADAY", "장중 점검", false),
  /** 07:30 아침 점검: 밤사이 미국 마감을 β 로 환산한 예상 갭으로 직전 판단을 유지/강화/주의 판정 (규칙 기반, 원 판단 불변) */
  MORNING_CHECK("MORNING_CHECK", "아침 해외 반영 점검", false),
  WEEKLY_REVIEW("WEEKLY_REVIEW", "주간 검토 (가중치·보정·교훈·보고)", true),
  IC_BACKFILL("IC_BACKFILL", "시그널 IC 사전 추정", true);

  private final String code;
  private final String desc;

  /**
   * API 로 트리거될 때 HTTP 스레드가 아니라 advisorExecutor 에 제출해 202 로 돌려줄 잡인지.
   * 스케줄러에서 부를 때는 이 값과 무관하게 스케줄러 스레드에서 동기 실행된다(ShedLock 락 유지).
   */
  private final boolean longRunning;
}
