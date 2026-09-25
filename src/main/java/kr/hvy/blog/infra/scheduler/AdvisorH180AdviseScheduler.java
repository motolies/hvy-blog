package kr.hvy.blog.infra.scheduler;

import java.time.LocalDate;
import kr.hvy.blog.modules.advisor.application.service.AdvisorOrchestrator;
import kr.hvy.blog.modules.advisor.application.service.LongTermCadence;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
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
 * AI 180거래일 규칙 추천 (M8, 평일 20:30 KST 에 깨어나 그 달 첫 거래일만). 장기 팩터 규칙 상위 N 을 확정하고 LLM 은 서술만 붙인다.
 * 주기가 아닌 날은 run 없이 조용히 돌아간다(ADVISE 게이트와 같은 방식 — SKIPPED run 을 매번 쌓지 않게). 주기 판정은 {@link LongTermCadence}.
 * 휴장·입력 미준비·이미 추천함은 잡이 SKIPPED 로 닫는다. yml scheduler.advisor-h180-advise.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-h180-advise.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorH180AdviseScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;
  private final MarketCalendarService calendar;

  @Scheduled(cron = "${scheduler.advisor-h180-advise.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-h180-advise.lock-name:ADVISOR-H180-ADVISE}", lockAtMostFor = "25m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-H180-ADVISE")
        .accept(() -> {
          LocalDate today = MarketClock.today();
          if (!LongTermCadence.isFirstTradingDayOfMonth(today, calendar)) {
            log.info("ADVISE_H180 주기 아님(run 없음): {}", today);
            return;
          }
          orchestrator.trigger(AdvisorJobType.ADVISE_H180, today, AdvisorTriggerType.SCHEDULER);
        });
  }
}
