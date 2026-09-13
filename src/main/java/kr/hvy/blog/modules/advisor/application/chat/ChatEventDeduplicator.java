package kr.hvy.blog.modules.advisor.application.chat;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Slack event_id 중복 제거 1차 방어선 — Socket Mode 는 ack 를 못 받으면 같은 envelope 를 재전송하고, 재연결 직후에도 같은 이벤트가 두 번 올 수 있다.
 * {@code RBucket.setIfAbsent(value, ttl)} 은 Redis {@code SET NX EX} 라 원자적이고 키가 스스로 사라진다(PostViewDeduplicator 와 같은 관용구).
 * Redis 장애 시에는 "처음 본 것" 으로 폴백한다 — DB 의 uk_advisor_chat_event 가 2차 방어선이라 최악은 INSERT 충돌 1건이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChatEventDeduplicator {

  static final String KEY_PREFIX = "advisor:chat:event:";

  private final RedissonClient redissonClient;
  private final AdvisorChatProperties properties;

  /**
   * 처음 보는 event_id 면 true. 이미 본 것이면 false.
   */
  public boolean firstSeen(String eventId) {
    if (eventId == null || eventId.isBlank()) {
      return true;
    }
    try {
      RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + eventId);
      return bucket.setIfAbsent("1", Duration.ofMinutes(Math.max(1, properties.getDedupTtlMinutes())));
    } catch (Exception e) {
      log.warn("Slack 이벤트 중복 판정 실패 — 처음 본 것으로 진행(DB 유니크가 2차 방어): eventId={}, cause={}", eventId, e.getMessage());
      return true;
    }
  }
}
