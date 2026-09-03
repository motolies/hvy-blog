package kr.hvy.blog.modules.stock.application.service;

import kr.hvy.blog.modules.stock.domain.code.CollectJobType;

/**
 * 수집 잡 1개. 오케스트레이터가 run 생명주기(생성·카운터·종료·Slack)를 감싸므로 구현체는 본문만 쓴다.
 * 종목 단위 실패는 예외를 던지지 말고 {@link CollectExecution#recordFailure} 로 누적한다.
 * 잡 전체를 중단해야 하는 오류만 예외로 전파한다(run FAILED).
 */
public interface CollectJob {

  CollectJobType jobType();

  void execute(CollectExecution execution);
}
