package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 180거래일 규칙 추천 잡(ADVISE_H180, M8 — 매월 첫 거래일 20:30 KST). 주기는 스케줄러가 {@link LongTermCadence#isFirstTradingDayOfMonth} 로 판정하고, 수동 트리거는 주기와 무관하게 돈다.
 * 파이프라인은 {@link LongTermAdviseJob} 이 공유한다.
 */
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class H180AdviseJob implements AdvisorJob {

  private final LongTermAdviseJob longTermAdviseJob;

  @Override
  public AdvisorJobType jobType() {
    return AdvisorJobType.ADVISE_H180;
  }

  @Override
  public void execute(AdvisorExecution execution) {
    longTermAdviseJob.advise(execution, AdviceKind.H180);
  }
}
