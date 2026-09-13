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
 * AI 주간 검토 (일요일 08:00 KST, WEEKLY 수집 03:00 종료 후). 확정 재채점·IC·가중치 세트·교훈·재현성·주간 보고·보존 정리.
 * yml scheduler.advisor-weekly-review.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-weekly-review.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorWeeklyReviewScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.advisor-weekly-review.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-weekly-review.lock-name:ADVISOR-WEEKLY-REVIEW}", lockAtMostFor = "30m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-WEEKLY-REVIEW")
        .accept(() -> orchestrator.trigger(AdvisorJobType.WEEKLY_REVIEW, MarketClock.today(), AdvisorTriggerType.SCHEDULER));
  }
}
