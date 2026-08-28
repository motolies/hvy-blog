package kr.hvy.blog.modules.stats.application.dto;

/**
 * 스케줄러 상태.
 * <p>
 * ⚠️ 여기에 SUCCESS/FAILURE 가 없는 것은 의도적이다.
 * {@code AbstractScheduler.proceedScheduler()} 가 예외를 삼키고 로그만 남기며
 * (catch 후 log.error), 실패해도 ShedLock 은 정상 해제되어 locked_at 이 갱신된다.
 * 즉 shedlock 은 "실행했다"만 말하고 "성공했다"는 말하지 않는다.
 * 성공/실패 추적은 hvy-common 수정이 필요하므로 여기서는 <b>지연 여부만</b> 판정한다.
 */
public enum SchedulerHealthState {
  /** 지금 실행 중 (lock_until 이 미래). */
  RUNNING,
  /** 예상 주기 안에 실행됨. */
  OK,
  /** 예상 주기의 3배가 지나도록 실행 기록이 갱신되지 않음. */
  STALE,
  /** shedlock 행이 아예 없음 — 한 번도 실행되지 않았다. */
  NEVER_RUN,
  /** 설정으로 꺼져 있음. */
  DISABLED
}
