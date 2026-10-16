package kr.hvy.blog.infra.scheduler;

import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService;
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
 * AI 180거래일 규칙 추천 (M8, 평일 20:30 KST 에 깨어나 그 달 첫 거래일 — 2026-10-16 부터는 첫 5거래일 안에서 그 달 LIVE H180 이 없으면).
 * 장기 팩터 규칙 상위 N 을 확정하고 LLM 은 서술만 붙인다. 첫 거래일이 아닌 따라잡기 실행은 run 메타에 catchUp·tradingDayOfMonth 를 남긴다.
 * 주기가 아닌 날은 run 없이 조용히 돌아간다(ADVISE 게이트와 같은 방식 — SKIPPED run 을 매번 쌓지 않게). 주기 판정은 {@link AdvisorGateService#h180Due}·{@link LongTermCadence}.
 * 휴장·입력 미준비·이미 추천함은 잡이 SKIPPED 로 닫는다. yml scheduler.advisor-h180-advise.enabled 로 on/off (기동 시 평가).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-h180-advise.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorH180AdviseScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;
  private final AdvisorGateService gate;
  private final MarketCalendarService calendar;

  @Scheduled(cron = "${scheduler.advisor-h180-advise.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-h180-advise.lock-name:ADVISOR-H180-ADVISE}", lockAtMostFor = "25m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-H180-ADVISE")
        .accept(() -> {
          LocalDate today = MarketClock.today();
          if (!gate.h180Due(today)) {
            log.info("ADVISE_H180 주기 아님 또는 이번 달 발행됨(run 없음): {}", today);
            return;
          }
          int day = LongTermCadence.tradingDayOfMonth(today, calendar);
          if (day == 1) {
            orchestrator.trigger(AdvisorJobType.ADVISE_H180, today, AdvisorTriggerType.SCHEDULER);
          } else {
            log.info("ADVISE_H180 따라잡기: {} (그 달 {}번째 거래일)", today, day);
            orchestrator.trigger(AdvisorJobType.ADVISE_H180, today, AdvisorTriggerType.SCHEDULER,
                Map.of("catchUp", true, "tradingDayOfMonth", day));
          }
        });
  }
}
