package kr.hvy.blog.modules.advisor.application.service;

import lombok.Getter;

/**
 * 관리자 취소 요청이 단계·청크 경계에서 감지됐을 때 잡 본문을 빠져나오는 신호. AdvisorSteps 의 격리 catch 는 이 예외를 삼키지 않고,
 * 오케스트레이터는 FAILED 알림 없이 조용히 닫는다(run 은 취소 요청 시점에 이미 CANCELED).
 */
@Getter
public class AdvisorCanceledException extends RuntimeException {

  private final Long runId;
  private final String step;

  public AdvisorCanceledException(Long runId, String step) {
    super("advisor run " + runId + " 취소 요청으로 중단 (단계 " + step + ")");
    this.runId = runId;
    this.step = step;
  }
}
