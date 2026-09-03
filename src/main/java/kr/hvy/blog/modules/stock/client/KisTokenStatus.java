package kr.hvy.blog.modules.stock.client;

import java.time.Duration;
import java.time.Instant;

/**
 * 관리자 화면용 토큰 상태. 토큰 값 자체는 절대 노출하지 않는다.
 */
public record KisTokenStatus(boolean configured, boolean present, Instant issuedAt, Instant expiresAt, long remainingMinutes) {

  /**
   * 토큰이 없을 때의 상태.
   */
  public static KisTokenStatus absent(boolean configured) {
    return new KisTokenStatus(configured, false, null, null, 0);
  }

  /**
   * 토큰 엔티티 값으로 상태를 만든다.
   */
  public static KisTokenStatus of(boolean configured, Instant issuedAt, Instant expiresAt) {
    long remaining = Math.max(0, Duration.between(Instant.now(), expiresAt).toMinutes());
    return new KisTokenStatus(configured, true, issuedAt, expiresAt, remaining);
  }
}
