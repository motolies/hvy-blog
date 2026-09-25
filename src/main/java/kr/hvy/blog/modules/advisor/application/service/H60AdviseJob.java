package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 60거래일 규칙 추천 잡(ADVISE_H60, M8 — 짝수 ISO 주 금요일 20:20 KST). 주기는 스케줄러가 {@link LongTermCadence#isH60Day} 로 판정하고, 수동 트리거는 주기와 무관하게 돈다.
 * 파이프라인은 {@link LongTermAdviseJob} 이 공유한다.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class H60AdviseJob implements AdvisorJob {

  private final LongTermAdviseJob longTermAdviseJob;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.ADVISE_H60;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    longTermAdviseJob.advise(execution, AdviceKind.H60);
  }
}
