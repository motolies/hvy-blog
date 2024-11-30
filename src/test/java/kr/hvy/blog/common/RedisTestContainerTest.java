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
public class RedisTestContainerTest extends AbstractTestContainers {

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