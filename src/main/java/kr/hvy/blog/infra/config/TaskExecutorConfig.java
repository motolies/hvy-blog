package kr.hvy.blog.infra.config;

import kr.hvy.common.config.executor.TaskExecutorConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Profile("!test")
@Configuration
@EnableAsync
public class TaskExecutorConfig extends TaskExecutorConfigurer {

  @Primary
  @Bean(name = "taskExecutor")
  public TaskExecutor taskExecutor() {
    return taskExecutor(20, 50, 10000, "AsyncTask-");
  }
}
