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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
    ts.setPoolSize(4); // 주식 일일 수집(최대 20분)이 1스레드를 장기 점유하므로 2 → 4 로 확대 (나머지 5개 잡은 3스레드로 충분)
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

  /**
   * 주식 백필 전용 실행기.
   * <p>
   * 백필은 3시간 이상 실행되므로 platformTaskScheduler(pool 4)를 점유하면 다른 스케줄러가 굶는다.
   * 관리자 REST 트리거가 여기에 제출하고 즉시 202 로 응답한다. 단일 스레드로 두어 백필끼리도 직렬화한다
   * (KIS 레이트 리미터를 공유하므로 병렬 실행에 이득이 없다). 중복 실행 차단은 tb_stock_collect_run 의
   * RUNNING 부분 유니크 인덱스가 담당한다. 백필은 체크포인트로 재개 가능하므로 종료 시 완료를 기다리지 않는다.
   */
  @Bean(name = "kisBackfillExecutor", destroyMethod = "shutdown")
  public ThreadPoolTaskExecutor kisBackfillExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(10);
    executor.setThreadNamePrefix("kis-backfill-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.initialize();
    return executor;
  }

  // 스케줄러 강제 지정 (여러 스케줄러/Executor가 있을 때 안전)
  @Bean
  public SchedulingConfigurer schedulingConfigurer(@Qualifier("platformTaskScheduler") TaskScheduler scheduler) {
    return taskRegistrar -> taskRegistrar.setTaskScheduler(scheduler);
  }
}
