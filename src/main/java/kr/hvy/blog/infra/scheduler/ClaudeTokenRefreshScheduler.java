package kr.hvy.blog.infra.scheduler;

import kr.hvy.blog.modules.claude.application.service.ClaudeCodeRefreshService;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!default")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.claude.enabled", havingValue = "true", matchIfMissing = true)
public class ClaudeTokenRefreshScheduler extends AbstractScheduler {

  private final ClaudeCodeRefreshService claudeCodeRefreshService;

  @Scheduled(cron = "0 50 5 * * ?", zone = "Asia/Seoul")
  @Scheduled(cron = "0 0 11 * * ?", zone = "Asia/Seoul")
  @Scheduled(cron = "0 10 16 * * ?", zone = "Asia/Seoul")
  @SchedulerLock(name = "${scheduler.claude.lock-name:CLAUDE-REFRESH}", lockAtMostFor = "5m", lockAtLeastFor = "1m")
  public void refreshToken() {
    proceedScheduler("CLAUDE-TOKEN-REFRESH")
        .accept(() -> claudeCodeRefreshService.refreshAndPing());
  }
}
