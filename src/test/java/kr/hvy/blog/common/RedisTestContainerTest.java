package kr.hvy.blog.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;


@Testcontainers
@SpringBootTest
public class RedisTestContainerTest {

  private static final String REDIS_IMAGE = "redis:7.0.8-alpine";
  private static final int REDIS_PORT = 6379;
  private static final GenericContainer REDIS_CONTAINER;

  static {
    REDIS_CONTAINER = new GenericContainer(REDIS_IMAGE)
        .withExposedPorts(REDIS_PORT)
        .withReuse(true);
    REDIS_CONTAINER.start();
  }

  @DynamicPropertySource
  private static void registerRedisProperties(DynamicPropertyRegistry registry) {
    // application-test.yml 이 있더라도 아래 부분이 추가되어 있어야 정상동작한다
    // 안그러면 host를 제대로 가져오지 못해서 접속이 되지 않음
    registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
    registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(REDIS_PORT)
        .toString());
  }

  @Autowired
  private RedisTemplate<String, String> redisTemplate;
  private ValueOperations<String, String> valueOps;

  @Test
  void testRedisSetAndGet() {
    valueOps = redisTemplate.opsForValue();

    // 값 설정
    valueOps.set("testKey", "testValue");

    // 값 가져오기
    String value = valueOps.get("testKey");

    // 검증
    assertThat(value).isEqualTo("testValue");
  }
}