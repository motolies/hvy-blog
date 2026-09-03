package kr.hvy.blog.modules.stock.application.service;

import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import lombok.Getter;

/**
 * 동일 잡이 이미 RUNNING 이어서 새 run 을 만들 수 없을 때. 부분 유니크 인덱스 위반을 변환한 것이다.
 * 관리자 API 는 이 예외를 409 로 응답하며, 평범한 조작 실수라 전역 핸들러(Slack 알림)로 보내지 않는다.
 */
@Getter
public class CollectAlreadyRunningException extends RuntimeException {

  private final CollectJobType jobType;
  private final Long runningRunId;

  public CollectAlreadyRunningException(CollectJobType jobType, Long runningRunId) {
    super(String.format("%s 잡이 이미 실행 중입니다 (runId=%s)", jobType, runningRunId));
    this.jobType = jobType;
    this.runningRunId = runningRunId;
  }
}
