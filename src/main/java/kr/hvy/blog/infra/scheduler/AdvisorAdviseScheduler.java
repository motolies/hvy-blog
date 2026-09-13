package kr.hvy.blog.infra.scheduler;

import java.time.LocalDate;
import java.time.LocalTime;
import kr.hvy.blog.modules.advisor.application.service.AdvisorGateService;
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
 * AI 일일 판단 (평일 19:30~19:55 KST, 5분 간격 6회). DAILY(18:30, ≈25분)가 끝났는지 게이트로 확인해 아니면 run 없이 조용히 돌아간다 —
 * 스레드를 잡고 sleep 하면 platformTaskScheduler(pool 4)를 30분 점유하므로 깨어나서 보고 즉시 반환한다. 이미 판단이 있으면 멱등 종료.
 * 마감(advisor.advise.deadline)이 지나도 미완료면 잡이 SKIPPED run 을 남기고 #hvy-error 로 알린다.
 * <p>
 * 트리거 거부(설정 누락·이미 실행 중)는 오케스트레이터가 #hvy-error 로 알린 뒤 다시 던지고 proceedScheduler 가 로그로 삼킨다.
 * yml scheduler.advisor-advise.enabled 로 on/off (기동 시 평가). advisor.enabled=false 면 오케스트레이터 빈이 없어 이 클래스도 뜨지 않는다.
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"scheduler.advisor-advise.enabled", "advisor.enabled"}, havingValue = "true")
public class AdvisorAdviseScheduler extends AbstractScheduler {

  private final AdvisorOrchestrator orchestrator;
  private final AdvisorGateService gate;

  @Scheduled(cron = "${scheduler.advisor-advise.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.advisor-advise.lock-name:ADVISOR-ADVISE}", lockAtMostFor = "25m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("ADVISOR-ADVISE")
        .accept(() -> {
          LocalDate today = MarketClock.today();
          AdvisorGateService.Decision decision = gate.decide(today, LocalTime.now(MarketClock.KST));
          if (decision.waitQuietly()) {
            log.info("ADVISE 대기/생략(run 없음): {}", decision.reason());
            return;
          }
          orchestrator.trigger(AdvisorJobType.ADVISE, today, AdvisorTriggerType.SCHEDULER);
        });
  }
}
