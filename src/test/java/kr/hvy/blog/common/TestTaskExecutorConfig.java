package kr.hvy.blog.common;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@Profile("test")
@Configuration
public class TestTaskExecutorConfig {

  /**
   * 운영의 {@code @Primary virtualThreadExecutor} 대체. {@code @Primary} 가 있어야
   * {@code kisBackfillExecutor} 등 다른 TaskExecutor 빈과 섞였을 때 {@code @Async} 기본 실행기가
   * 이 빈으로 확정된다(없으면 후보가 둘이라 해석이 불안정하다).
   */
  @Bean
  @Primary
  @Qualifier("virtualThreadExecutor")
  public TaskExecutor virtualThreadExecutor() {
    return new SyncTaskExecutor();
  }

  /**
   * 주식 백필 전용 실행기의 테스트 대체. 운영은 단일 스레드 풀이지만 테스트는 호출 스레드에서 동기 실행한다.
   */
  @Bean(name = "kisBackfillExecutor")
  public TaskExecutor kisBackfillExecutor() {
    return new SyncTaskExecutor();
  }
}
