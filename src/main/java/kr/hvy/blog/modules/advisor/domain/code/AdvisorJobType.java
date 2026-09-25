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
  /**
   * 07:40 아침 재판정(advice_kind=MORNING, M4): 전일 저녁 LIVE 판단을 밤사이 정보로 다시 보고 KEEP/DROP/ADD 한다. 저녁과 같은 base_date·진입/청산 창이라
   * MORNING − DAILY 가 밤사이 정보의 가치를 재는 대응 비교가 된다. 07:30 MORNING_CHECK(규칙 점검)와 유형을 나눈 이유는 RUNNING 부분 유니크가 유형 단위이기 때문
   */
  MORNING_ADVISE("MORNING_ADVISE", "아침 재판정", true),
  WEEKLY_REVIEW("WEEKLY_REVIEW", "주간 검토 (가중치·보정·교훈·보고)", true),
  IC_BACKFILL("IC_BACKFILL", "시그널 IC 사전 추정", true),
  /**
   * 채팅 봇 requestAdvice 가 여는 수시 판단(advice_kind=ADHOC, chat-v2). ADVISE 와 잡 유형을 나눈 이유: RUNNING 부분 유니크가 잡 유형 단위라
   * 같은 유형이면 채팅 요청이 도는 동안 19:30 스케줄 ADVISE 가 "이미 실행 중" 으로 거부된다.
   */
  ADVISE_ADHOC("ADVISE_ADHOC", "수시 판단(채팅 요청)", true),
  /**
   * 금요일 20:10 주간 20거래일 판단(advice_kind=H20, M7). 파이프라인은 ADVISE 와 공유하되(AdviseJob.advise(kind)) 뉴스·메모리 없이 H20 가중치 세트로만 돈다.
   * ADVISE 와 유형을 나눈 이유는 ADVISE_ADHOC 과 같다 — RUNNING 부분 유니크가 잡 유형 단위라 같은 유형이면 서로를 "이미 실행 중" 으로 막는다.
   */
  ADVISE_H20("ADVISE_H20", "20거래일 주간 판단", true);

  private final String code;
  private final String desc;

  /**
   * API 로 트리거될 때 HTTP 스레드가 아니라 advisorExecutor 에 제출해 202 로 돌려줄 잡인지.
   * 스케줄러에서 부를 때는 이 값과 무관하게 스케줄러 스레드에서 동기 실행된다(ShedLock 락 유지).
   */
  private final boolean longRunning;
}
