package kr.hvy.blog.modules.stock.client;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import kr.hvy.blog.modules.stock.domain.entity.StockKisToken;
import kr.hvy.blog.modules.stock.repository.StockKisTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KIS 접근토큰 제공자. 프로세스 내 캐시 → DB(발급기) 순으로 조회한다.
 * <p>
 * 규칙: ① 만료 10분 전부터 갱신 ② 발급은 1분 1회이므로 게이트에 걸리면 기존 토큰 유지
 * ③ 인프로세스 {@link ReentrantLock} + 발급기의 분산락으로 크론/수동/다중 인스턴스 경합을 직렬화한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenManager {

  private final KisTokenIssuer issuer;
  private final StockKisTokenRepository repository;
  private final KisProperties properties;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile StockKisToken cached;

  /**
   * 유효한 접근토큰을 돌려준다. 캐시가 유효하면 DB 를 보지 않는다.
   */
  public String getAccessToken() {
    StockKisToken token = cached;
    if (token != null && token.isValidAt(Instant.now(), buffer())) {
      return token.getAccessToken();
    }
    lock.lock();
    try {
      token = cached;
      if (token != null && token.isValidAt(Instant.now(), buffer())) {
        return token.getAccessToken();
      }
      token = issuer.issue(false);
      cached = token;
      return token.getAccessToken();
    } finally {
      lock.unlock();
    }
  }

  /**
   * 401 등 토큰 무효 응답을 받은 호출부가 강제 갱신을 요청한다. 1분 게이트에 걸리면 기존 토큰이 그대로 돌아온다.
   */
  public String forceRefresh() {
    lock.lock();
    try {
      StockKisToken token = issuer.issue(true);
      cached = token;
      return token.getAccessToken();
    } finally {
      lock.unlock();
    }
  }

  /**
   * 장시간 잡 시작 전에 호출한다. 예상 소요 시간 안에 만료될 토큰이면 선제 갱신한다.
   */
  public void ensureValidFor(Duration expectedDuration) {
    StockKisToken token = current();
    Instant needUntil = Instant.now().plus(expectedDuration).plus(buffer());
    if (token == null || token.getExpiresAt().isBefore(needUntil)) {
      log.info("KIS 토큰 잔여 시간이 예상 소요({})보다 짧아 선제 갱신합니다", expectedDuration);
      forceRefresh();
    }
  }

  /**
   * 관리자 화면용 상태. 토큰 값은 포함하지 않는다.
   */
  public KisTokenStatus status() {
    StockKisToken token = current();
    if (token == null) {
      return KisTokenStatus.absent(properties.isConfigured());
    }
    return KisTokenStatus.of(properties.isConfigured(), token.getIssuedAt(), token.getExpiresAt());
  }

  private StockKisToken current() {
    StockKisToken token = cached;
    if (token == null) {
      token = repository.findById(StockKisToken.KEY_REAL).orElse(null);
      cached = token;
    }
    return token;
  }

  private Duration buffer() {
    return properties.getToken().getRefreshBuffer();
  }
}
