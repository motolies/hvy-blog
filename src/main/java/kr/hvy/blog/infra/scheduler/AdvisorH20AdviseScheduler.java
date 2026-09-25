package kr.hvy.blog.infra.scheduler;

import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.stock.domain.model.MarketClock;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 주간 20거래일 판단 (금요일 20:10 KST, M7). 일일 수집(18:30)·ADVISE(19:30~19:55) 뒤라 그날 지표·DAILY 채점이 끝난 상태에서 돈다.
 * 휴장·입력 미준비·H20 가중치 세트 없음·이미 판단함은 잡이 SKIPPED 로 닫는다. yml scheduler.advisor-h20-advise.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-h20-advise.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorH20AdviseScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.advisor-h20-advise.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-h20-advise.lock-name:ADVISOR-H20-ADVISE}", lockAtMostFor = "25m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-H20-ADVISE")
        .accept(() -> orchestrator.trigger(AdvisorJobType.ADVISE_H20, MarketClock.today(), AdvisorTriggerType.SCHEDULER));
  }
}
