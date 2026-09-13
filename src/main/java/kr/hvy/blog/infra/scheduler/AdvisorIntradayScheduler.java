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
 * AI 장중 점검 (평일 12:00 KST). 직전 영업일 판단을 KIS 현재가로 대조해 짧게 보고한다(규칙 기반, 학습 미반영). 휴장일·판단 없음은 잡이 SKIPPED 로 닫는다.
 * yml scheduler.advisor-intraday.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-intraday.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorIntradayScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.advisor-intraday.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-intraday.lock-name:ADVISOR-INTRADAY}", lockAtMostFor = "10m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-INTRADAY")
        .accept(() -> orchestrator.trigger(AdvisorJobType.INTRADAY, MarketClock.today(), AdvisorTriggerType.SCHEDULER));
  }
}
