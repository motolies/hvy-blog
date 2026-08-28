package kr.hvy.blog.modules.post.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 조회수 중복 집계 방지 — "이 IP 가 이 글을 최근에 이미 봤는가"를 원자적으로 판정한다.
 * <p>
 * hvy-common 의 {@code @DistributedRateLimit} 을 쓰지 않는 이유:
 * <ul>
 *   <li>Redisson RRateLimiter 기반 <b>처리율 제한기</b>이지 중복 제거기가 아니다.</li>
 *   <li>{@code timeout()} 기본값이 0 이면 {@code limiter.acquire()} 로 <b>무한 블로킹</b>한다 —
 *       공개 엔드포인트에 붙이면 톰캣 스레드가 고갈된다.</li>
 *   <li>초과 시 예외를 던져 500 + Slack 알림으로 이어진다. 중복 조회는 조용한 no-op 이어야 한다.</li>
 *   <li>키를 {@code view:{postId}:{ip}} 로 잡으면 RRateLimiter 키가 TTL 없이 무한 증식한다.</li>
 * </ul>
 * 여기서 쓰는 {@code RBucket.setIfAbsent(value, ttl)} 은 Redis {@code SET NX EX} 와 같고,
 * TTL 이 붙으므로 키가 스스로 사라진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostViewDeduplicator {

  /** 같은 방문자의 같은 글 재조회를 이 기간 동안 한 번으로 센다. */
  private static final Duration DEDUP_TTL = Duration.ofHours(6);
  private static final String KEY_PREFIX = "postview:";

  private final RedissonClient redissonClient;

  /**
   * 집계 대상이면 true. 이미 센 방문이면 false.
   * <p>
   * Redis 장애 시에는 true 로 폴백한다 — 집계가 조금 부풀더라도 조회수 자체가 멈추는 것보다 낫다.
   */
  public boolean isFirstView(Long postId, String remoteAddr) {
    try {
      String key = KEY_PREFIX + postId + ":" + shortHash(remoteAddr);
      RBucket<String> bucket = redissonClient.getBucket(key);
      return bucket.setIfAbsent("1", DEDUP_TTL);
    } catch (Exception e) {
      // 조회수 계측이 Redis 가용성에 의존하면 안 된다
      log.warn("조회수 중복 판정 실패 — 집계는 계속한다: postId={}, cause={}", postId, e.getMessage());
      return true;
    }
  }

  /** IP 를 그대로 Redis 키에 넣지 않는다 — 개인정보를 키 공간에 노출시킬 이유가 없다. */
  private String shortHash(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isBlank()) {
      return "unknown";
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(remoteAddr.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      return Integer.toHexString(remoteAddr.hashCode());
    }
  }
}
