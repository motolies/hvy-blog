package kr.hvy.blog.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Testcontainers
@SpringBootTest
public abstract class AbstractTestContainers {

  private static final String REDIS_IMAGE = "redis:7.0.8-alpine";
  private static final int REDIS_PORT = 6379;


  @Container
  private static final GenericContainer redisContainer = new GenericContainer(REDIS_IMAGE)
      .withExposedPorts(REDIS_PORT)
      .withReuse(true); // 컨테이너 재사용 설정

  static {
    redisContainer.start();
  }

  @DynamicPropertySource
  static void registerRedisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redisContainer::getHost);
    registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(REDIS_PORT).toString());
  }
}