package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;

/**
 * advisor 잡 1개. 구현체는 @Component 로 등록하면 AdvisorOrchestrator 가 jobType 별로 자동 수집한다.
 * <p>
 * 본문은 예외를 던져도 된다 — 오케스트레이터가 run 을 FAILED 로 닫고 #hvy-error 로 알린다.
 * 게이트 미충족(휴장일·DAILY 미완료)은 예외 대신 {@link AdvisorExecution#skip(String)} 으로 조용히 끝낸다.
 */
public interface AdvisorJob {

  AdvisorJobType jobType();

  void execute(AdvisorExecution execution);
}
