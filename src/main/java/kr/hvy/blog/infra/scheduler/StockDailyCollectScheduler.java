package kr.hvy.blog.infra.scheduler;

import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.application.service.StockCollectOrchestrator;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 증분 파이프라인 (평일 18:30 KST). 단계 순서는 StockDailyPipelineJob 이 단일 출처다.
 * <p>
 * run 생성·중복 차단·카운터·Slack 은 오케스트레이터가 처리한다. 트리거가 거부되면(키 누락·이미 실행 중·DB 오류)
 * 오케스트레이터가 run 없이 #hvy-error 로 알린 뒤 다시 던지고 proceedScheduler 가 로그로 삼킨다.
 * yml scheduler.stock-daily.enabled 로 on/off (기동 시 평가, 2026-09-13 prod 활성화).
 */
@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.stock-daily.enabled", havingValue = "true")
public class StockDailyCollectScheduler extends AbstractScheduler {

  private final StockCollectOrchestrator orchestrator;

  @Scheduled(cron = "${scheduler.stock-daily.cron-expression}", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.stock-daily.lock-name:STOCK-DAILY}", lockAtMostFor = "40m", lockAtLeastFor = "1m")
  public void run() {
    proceedScheduler("STOCK-DAILY")
        .accept(() -> {
          orchestrator.trigger(CollectJobType.DAILY, BackfillRequest.empty(), TriggerType.SCHEDULER);
        });
  }
}
