package kr.hvy.blog.common;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@Profile("test")
@Configuration
public class TestTaskExecutorConfig {

  @Bean
  @Qualifier("virtualThreadExecutor")
  public TaskExecutor virtualThreadExecutor() {
    return new SyncTaskExecutor();
  }
}
