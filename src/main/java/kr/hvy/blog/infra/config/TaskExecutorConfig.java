package kr.hvy.blog.infra.config;

import java.util.concurrent.ExecutorService;
import kr.hvy.common.config.executor.TaskExecutorConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Profile("!test")
@Configuration
@EnableAsync
public class TaskExecutorConfig extends TaskExecutorConfigurer {

  @Bean(name = "vtExecutorService", destroyMethod = "shutdown")
  public ExecutorService vtExecutorService() {
    return super.vtExecutorService("Async-vt-");
  }

  @Primary
  @Bean(name = "virtualThreadExecutor")
  public TaskExecutor virtualThreadExecutor(@Qualifier("vtExecutorService") ExecutorService executorService) {
    return super.virtualThreadExecutor(executorService);
  }

  // 스케줄러 전용: 플랫폼 스레드 쓰는 ThreadPoolTaskScheduler (ShedLock 사용)
  @Bean(name = "platformTaskScheduler")
  public ThreadPoolTaskScheduler platformTaskScheduler() {
    var ts = new ThreadPoolTaskScheduler();
    ts.setPoolSize(2); // ShedLock 처리를 위해 증가
    ts.setThreadNamePrefix("sched-"); // 스케줄러 스레드 식별
    ts.setRemoveOnCancelPolicy(true);
    ts.setAwaitTerminationSeconds(30); // ShedLock 해제 대기 시간 증가
    ts.setWaitForTasksToCompleteOnShutdown(true); // 스케줄러 작업 완료 대기
    ts.setRejectedExecutionHandler((r, executor) -> {
      // ShedLock으로 인한 거부된 실행에 대한 로깅
      System.err.println("스케줄러 작업이 거부됨: " + r.toString());
    });
    ts.initialize(); // 명시적 초기화
    return ts;
  }

  // 스케줄러 강제 지정 (여러 스케줄러/Executor가 있을 때 안전)
  @Bean
  public SchedulingConfigurer schedulingConfigurer(@Qualifier("platformTaskScheduler") TaskScheduler scheduler) {
    return taskRegistrar -> taskRegistrar.setTaskScheduler(scheduler);
  }
}
