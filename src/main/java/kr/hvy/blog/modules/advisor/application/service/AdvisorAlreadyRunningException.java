package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import lombok.Getter;

/**
 * 같은 잡이 이미 RUNNING 일 때. 관리자 API 는 409 로 돌려준다.
 */
@Getter
public class AdvisorAlreadyRunningException extends RuntimeException {

  private final AdvisorJobType jobType;
  private final Long runningRunId;

  public AdvisorAlreadyRunningException(AdvisorJobType jobType, Long runningRunId) {
    super("이미 실행 중인 잡입니다: " + jobType + " (runId=" + runningRunId + ")");
    this.jobType = jobType;
    this.runningRunId = runningRunId;
  }
}
