package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 수시 판단 잡(ADVISE_ADHOC, chat-v2). 파이프라인은 {@link AdviseJob} 과 같고 저장 종류만 ADHOC 이다 — 입력·프롬프트·가드를 공유해야
 * "채팅에서 받은 판단" 이 19:30 판단과 같은 규칙으로 만들어졌다고 말할 수 있다. 잡 유형을 나눈 이유는 {@link AdvisorJobType#ADVISE_ADHOC} 참고.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdhocAdviseJob implements AdvisorJob {

  private final AdviseJob adviseJob;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.ADVISE_ADHOC;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    adviseJob.advise(execution, AdviceKind.ADHOC);
  }
}
