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
 * AI 아침 점검 (평일 07:30 KST, 06:30 해외 수집 뒤·09:00 개장 전). 밤사이 미국 마감을 β 로 환산한 예상 갭으로 직전 판단을 유지/강화/주의 판정한다
 * (규칙 기반, 원 판단 불변). 휴장일·판단 없음·미국 데이터 없음은 잡이 SKIPPED 로 닫는다. yml scheduler.advisor-morning-check.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-morning-check.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorMorningCheckScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.advisor-morning-check.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-morning-check.lock-name:ADVISOR-MORNING-CHECK}", lockAtMostFor = "10m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-MORNING-CHECK")
        .accept(() -> orchestrator.trigger(AdvisorJobType.MORNING_CHECK, MarketClock.today(), AdvisorTriggerType.SCHEDULER));
  }
}
