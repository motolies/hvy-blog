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
 * AI 아침 재판정 (평일 07:40 KST, 07:30 아침 점검 뒤·08:50 발행 마감 전). 전일 저녁 LIVE 판단을 밤사이 미국 정보로 다시 보고 KEEP/DROP/ADD 한 MORNING 판단을
 * 저녁과 같은 기준일·창으로 저장한다(LLM 매일 호출). 휴장일·저녁 판단 없음·이미 재판정·마감 이후는 잡이 SKIPPED 로 닫는다.
 * yml scheduler.advisor-morning-advise.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-morning-advise.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorMorningAdviseScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.advisor-morning-advise.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-morning-advise.lock-name:ADVISOR-MORNING-ADVISE}", lockAtMostFor = "15m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-MORNING-ADVISE")
        .accept(() -> orchestrator.trigger(AdvisorJobType.MORNING_ADVISE, MarketClock.today(), AdvisorTriggerType.SCHEDULER));
  }
}
