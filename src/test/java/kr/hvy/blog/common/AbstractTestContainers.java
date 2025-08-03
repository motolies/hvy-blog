package kr.hvy.blog.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.Duration;

@Testcontainers
@SpringBootTest
public abstract class AbstractTestContainers {

  private static final String REDIS_IMAGE = "redis:7.0.8-alpine";
  private static final int REDIS_PORT = 6379;


  @Container
  @SuppressWarnings("resource") // Testcontainers가 자체적으로 리소스를 관리합니다
  private static final GenericContainer<?> redisContainer = new GenericContainer<>(REDIS_IMAGE)
      .withExposedPorts(REDIS_PORT)
      .withCommand("redis-server", "--maxmemory", "128m", "--maxmemory-policy", "allkeys-lru")
      .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)
          .withStartupTimeout(Duration.ofSeconds(30)));

  static {
    redisContainer.start();
  }

  @DynamicPropertySource
  static void registerRedisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redisContainer::getHost);
    registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(REDIS_PORT).toString());
  }
}