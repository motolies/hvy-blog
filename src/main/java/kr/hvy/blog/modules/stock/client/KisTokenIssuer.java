package kr.hvy.blog.modules.stock.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.stock.client.dto.KisTokenResponse;
import kr.hvy.blog.modules.stock.domain.entity.StockKisToken;
import kr.hvy.blog.modules.stock.repository.StockKisTokenRepository;
import kr.hvy.common.infrastructure.redis.lock.DistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * KIS 접근토큰 발급기. 분산락 안에서 DB 를 다시 읽어(double-check) 다른 인스턴스가 이미 갱신했는지 확인한다.
 * <p>
 * {@link KisTokenManager} 와 분리한 이유는 자기 호출로는 {@code @DistributedLock} AOP 프록시를 타지 못하기 때문이다.
 */
@Slf4j
@Component
public class KisTokenIssuer {

  private static final String TOKEN_PATH = "/oauth2/tokenP";
  private static final long DEFAULT_EXPIRES_IN_SECONDS = 86_400L;

  private final RestClient kisRestClient;
  private final KisProperties properties;
  private final StockKisTokenRepository repository;

  public KisTokenIssuer(@Qualifier("kisRestClient") RestClient kisRestClient, KisProperties properties,
      StockKisTokenRepository repository) {
    this.kisRestClient = kisRestClient;
    this.properties = properties;
    this.repository = repository;
  }

  /**
   * 유효한 토큰을 보장한다. 필요할 때만 발급하며, 발급 1분 제한에 걸리면 기존 토큰을 그대로 돌려준다.
   *
   * @param force true 면 만료 전이라도 재발급을 시도한다 (401 수신 시)
   */
  @DistributedLock(key = "'KIS_TOKEN_ISSUE'", waitTime = 30, leaseTime = 60)
  @Transactional
  public StockKisToken issue(boolean force) {
    Instant now = Instant.now();
    Optional<StockKisToken> existing = repository.findById(StockKisToken.KEY_REAL);

    if (existing.isPresent()) {
      StockKisToken current = existing.get();
      if (!force && current.isValidAt(now, properties.getToken().getRefreshBuffer())) {
        return current;
      }
      if (!current.canIssueAt(now, properties.getToken().getIssueInterval())) {
        log.warn("KIS 토큰 발급 최소 간격({}) 미경과 — 기존 토큰을 유지합니다 (issuedAt={})",
            properties.getToken().getIssueInterval(), current.getIssuedAt());
        return current;
      }
    }

    if (!properties.isConfigured()) {
      throw new IllegalStateException("KIS 앱키가 설정되지 않았습니다 (KIS_APP_KEY / KIS_APP_SECRET)");
    }

    KisTokenResponse response = requestToken();
    Instant issuedAt = Instant.now();
    long expiresIn = response.expiresIn() != null ? response.expiresIn() : DEFAULT_EXPIRES_IN_SECONDS;
    Instant expiresAt = issuedAt.plusSeconds(expiresIn);
    String tokenType = StringUtils.defaultIfBlank(response.tokenType(), "Bearer");

    StockKisToken token = existing
        .map(current -> {
          current.renew(response.accessToken(), tokenType, issuedAt, expiresAt);
          return current;
        })
        .orElseGet(() -> StockKisToken.builder()
            .tokenKey(StockKisToken.KEY_REAL)
            .accessToken(response.accessToken())
            .tokenType(tokenType)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .updatedAt(issuedAt)
            .build());

    StockKisToken saved = repository.save(token);
    log.info("KIS 접근토큰 발급 완료: expiresAt={}", expiresAt);
    return saved;
  }

  /**
   * 토큰 엔드포인트를 1회 호출한다. 비-2xx 나 access_token 누락은 즉시 실패로 본다(재시도는 상위 정책).
   */
  private KisTokenResponse requestToken() {
    Map<String, String> body = Map.of(
        "grant_type", "client_credentials",
        "appkey", properties.getAppKey(),
        "appsecret", properties.getAppSecret());

    return kisRestClient.post()
        .uri(TOKEN_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange((request, response) -> {
          String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(String.format("KIS 토큰 발급 실패: status=%s body=%s",
                response.getStatusCode(), StringUtils.abbreviate(responseBody, 500)));
          }
          KisTokenResponse parsed = KisJson.read(responseBody, KisTokenResponse.class);
          if (StringUtils.isBlank(parsed.accessToken())) {
            throw new IllegalStateException("KIS 토큰 발급 응답에 access_token 이 없습니다: "
                + StringUtils.abbreviate(responseBody, 500));
          }
          return parsed;
        });
  }
}
