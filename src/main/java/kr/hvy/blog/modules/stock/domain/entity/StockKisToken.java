package kr.hvy.blog.modules.stock.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Duration;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * KIS 접근토큰 영속 저장 (tb_stock_kis_token).
 * <p>
 * Redis 대신 테이블에 두는 이유: 배포 절차에 Redis 플러시가 있어 토큰이 소실되면 발급 1분 제한 때문에
 * 최대 1분의 공백이 생긴다. issued_at 을 함께 저장해 1분 게이트를 DB 값으로 직접 판정한다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockKisToken {

  public static final String KEY_REAL = "REAL";

  @Id
  @Column(length = 20)
  private String tokenKey;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String accessToken;

  @Column(nullable = false, length = 20)
  private String tokenType;

  @Column(nullable = false)
  private Instant issuedAt;

  @Column(nullable = false)
  private Instant expiresAt;

  @Column(columnDefinition = "TEXT")
  private String approvalKey;

  @Column(nullable = false)
  private Instant updatedAt;

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  /**
   * 새 토큰으로 교체한다.
   */
  public void renew(String accessToken, String tokenType, Instant issuedAt, Instant expiresAt) {
    this.accessToken = accessToken;
    this.tokenType = tokenType;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.updatedAt = Instant.now();
  }

  /**
   * 버퍼를 감안해 아직 유효한지 판정한다.
   */
  public boolean isValidAt(Instant now, Duration buffer) {
    return expiresAt.minus(buffer).isAfter(now);
  }

  /**
   * 발급 최소 간격이 지나 재발급이 허용되는지 판정한다.
   */
  public boolean canIssueAt(Instant now, Duration issueInterval) {
    return !issuedAt.plus(issueInterval).isAfter(now);
  }
}
