package kr.hvy.blog.modules.claude.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeUpdate;
import kr.hvy.blog.modules.admin.application.service.MasterCodeService;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeResponse;
import kr.hvy.blog.modules.claude.client.dto.ClaudeMessage;
import kr.hvy.blog.modules.claude.client.dto.ClaudeMessage.Message;
import kr.hvy.blog.modules.claude.client.dto.ClaudeMessage.Request;
import kr.hvy.blog.modules.claude.client.dto.ClaudeTokenResponse;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class ClaudeCodeRefreshService {

  private static final String MASTER_CODE_ROOT = "CLAUDE";
  private static final String ATTR_REFRESH_TOKEN = "refreshToken";
  private static final String ATTR_ACCESS_TOKEN = "accessToken";
  private static final String ATTR_EXPIRES_AT = "expiresAt";
  private static final long TOKEN_BUFFER_MILLIS = 5 * 60 * 1000L; // 만료 5분 전부터 갱신
  private static final String TOKEN_ENDPOINT = "https://platform.claude.com/v1/oauth/token";
  private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final String OAUTH_BETA = "oauth-2025-04-20";
  private static final String ANTHROPIC_BETA = "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,prompt-caching-scope-2026-01-05";
  private static final String CLI_VERSION = "2.1.101";
  private static final String USER_AGENT = "claude-cli/" + CLI_VERSION + " (external, cli)";
  private static final String BILLING_HEADER = "cc_version=" + CLI_VERSION + ".claude-sonnet-4-20250514; cc_entrypoint=cli; cch=00000;";
  private static final String SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude.";
  private static final String MODEL = "claude-sonnet-4-20250514";

  private static final int MAX_REFRESH_ATTEMPTS = 3;
  private static final long MAX_RETRY_BACKOFF_MILLIS = 120_000L; // Retry-After/백오프 상한 (2분)

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final RestClient claudeRestClient;
  private final MasterCodeService masterCodeService;
  private final Notify notify;

  // 크론(다중 시각)과 수동 트리거(/api/claude/admin/refresh)가 동시에 실행되어
  // 동일 refreshToken 을 중복 제출(단회성 토큰 회전 경쟁)하지 않도록 인프로세스 직렬화한다.
  private final ReentrantLock refreshLock = new ReentrantLock();

  public ClaudeCodeRefreshService(
      @Qualifier("claudeRestClient") RestClient claudeRestClient,
      MasterCodeService masterCodeService,
      Notify notify
  ) {
    this.claudeRestClient = claudeRestClient;
    this.masterCodeService = masterCodeService;
    this.notify = notify;
  }

  /**
   * 토큰 갱신 + ping 진입점.
   * <p>
   * 이미 다른 트리거(크론/수동)가 수행 중이면 즉시 건너뛴다. refreshToken 은 단회성으로 회전되므로
   * 동시 실행 시 한쪽이 토큰을 무효화시켜 나머지가 invalid_grant 로 영구 실패하는 것을 방지한다.
   */
  public void refreshAndPing() {
    if (!refreshLock.tryLock()) {
      log.warn("Claude 토큰 리프레시가 이미 실행 중이어서 이번 트리거는 건너뜁니다");
      return;
    }
    try {
      doRefreshAndPing();
    } finally {
      refreshLock.unlock();
    }
  }

  /**
   * CLAUDE 루트 하위 계정을 순회하며 각각 토큰 갱신 + ping 을 수행하고, 실패 계정을 집계해 최종 예외로 알린다.
   */
  private void doRefreshAndPing() {
    List<MasterCodeResponse> accounts = masterCodeService.getChildrenByRootCode(MASTER_CODE_ROOT);

    if (CollectionUtils.isEmpty(accounts)) {
      log.warn("MasterCode '{}' 하위에 등록된 계정이 없습니다", MASTER_CODE_ROOT);
      return;
    }

    List<String> failedAccounts = new ArrayList<>();

    for (MasterCodeResponse account : accounts) {
      try {
        refreshAccount(account);
      } catch (Exception e) {
        failedAccounts.add(account.getCode());
        log.error("[{}] Claude 토큰 리프레시 실패: {}", account.getCode(), e.getMessage(), e);
        notify.sendMessage(NotifyRequest.builder()
            .channel(SlackChannel.ERROR.getChannel())
            .exception(e)
            .isNotify(true)
            .build());
      }
    }

    if (!failedAccounts.isEmpty()) {
      throw new IllegalStateException(
          String.format("Claude 토큰 리프레시 실패 계정: %s (총 %d/%d)",
              String.join(", ", failedAccounts), failedAccounts.size(), accounts.size()));
    }
  }

  /**
   * 단일 계정의 토큰 갱신 + ping.
   * <p>
   * 캐시가 직전 실행의 갱신 결과를 아직 반영하지 못했을 가능성에 대비해, 갱신 직전 DB 에서 노드를 직접
   * 재조회(캐시 우회)하여 최신 refreshToken 을 사용한다. ping 실패는 토큰 갱신 성공을 가리지 않도록 분리 처리한다.
   */
  private void refreshAccount(MasterCodeResponse cachedAccount) {
    // 캐시 우회 재조회: getNode 는 masterCodeRepository.findById 로 DB 를 직접 읽는다(L1/L2 미경유)
    MasterCodeResponse account = masterCodeService.getNode(cachedAccount.getId());
    String accountName = account.getCode();
    Map<String, Object> attrs = account.getAttributes();
    String refreshToken = (String) attrs.get(ATTR_REFRESH_TOKEN);

    if (StringUtils.isBlank(refreshToken)) {
      throw new IllegalStateException(
          String.format("[%s] refreshToken이 비어있습니다", accountName));
    }

    // 1. accessToken이 만료되었거나 없으면 갱신
    String accessToken = (String) attrs.get(ATTR_ACCESS_TOKEN);

    if (isTokenExpired(attrs)) {
      ClaudeTokenResponse tokenResponse = refreshAccessToken(refreshToken);
      accessToken = tokenResponse.getAccessToken();
      log.info("[{}] OAuth 토큰 갱신 완료, expires_in={}s", accountName, tokenResponse.getExpiresIn());

      // 갱신된 토큰 정보를 MasterCode에 저장 (회전된 새 refreshToken 즉시 영속화)
      long expiresAt = System.currentTimeMillis() + (tokenResponse.getExpiresIn() * 1000);
      String newRefreshToken = StringUtils.isNotBlank(tokenResponse.getRefreshToken())
          ? tokenResponse.getRefreshToken() : refreshToken;

      updateTokenAttributes(account.getId(), attrs, accessToken, newRefreshToken, expiresAt);
    } else {
      log.info("[{}] accessToken 유효 (만료까지 {}분), 갱신 생략", accountName, remainingMinutes(attrs));
    }

    // 2. ping 대화 수행 (사용량 타이머 리셋) — 토큰 갱신은 이미 끝났으므로 ping 실패가 계정 실패로 전이되지 않게 격리
    try {
      sendPing(accountName, accessToken);
    } catch (Exception e) {
      log.warn("[{}] ping 실패(토큰 갱신은 정상): {}", accountName, e.getMessage());
      notify.sendMessage(NotifyRequest.builder()
          .channel(SlackChannel.ERROR.getChannel())
          .exception(e)
          .isNotify(true)
          .build());
    }
  }

  private long remainingMinutes(Map<String, Object> attrs) {
    Object expiresAtObj = attrs.get(ATTR_EXPIRES_AT);
    if (expiresAtObj == null) {
      return 0;
    }
    long expiresAt = expiresAtObj instanceof Number
        ? ((Number) expiresAtObj).longValue()
        : Long.parseLong(expiresAtObj.toString());
    return Math.max(0, (expiresAt - System.currentTimeMillis()) / 60_000);
  }

  private boolean isTokenExpired(Map<String, Object> attrs) {
    String accessToken = (String) attrs.get(ATTR_ACCESS_TOKEN);
    if (StringUtils.isBlank(accessToken)) {
      return true;
    }

    Object expiresAtObj = attrs.get(ATTR_EXPIRES_AT);
    if (expiresAtObj == null) {
      return true;
    }

    long expiresAt = expiresAtObj instanceof Number
        ? ((Number) expiresAtObj).longValue()
        : Long.parseLong(expiresAtObj.toString());

    return System.currentTimeMillis() >= (expiresAt - TOKEN_BUFFER_MILLIS);
  }

  /**
   * refresh_token 그랜트로 access_token 을 갱신한다.
   * <p>
   * 토큰 엔드포인트 앞단(Cloudflare)이 봇형 요청을 차단하므로, 정품 Claude CLI 와 동일한 헤더
   * (User-Agent / anthropic-beta / anthropic-version / x-app)를 함께 전송한다. 429/5xx 는 Retry-After 를
   * 존중해 재시도하고, 400 invalid_grant 같은 영구 오류는 재시도 없이 즉시 실패시킨다.
   */
  private ClaudeTokenResponse refreshAccessToken(String refreshToken) {
    Map<String, String> body = Map.of(
        "grant_type", "refresh_token",
        "refresh_token", refreshToken,
        "client_id", CLIENT_ID
    );

    RuntimeException lastException = null;

    for (int attempt = 1; attempt <= MAX_REFRESH_ATTEMPTS; attempt++) {
      try {
        return requestToken(body);
      } catch (TokenRefreshException e) {
        lastException = e;
        log.warn("토큰 갱신 시도 {}/{} 실패: status={} {}", attempt, MAX_REFRESH_ATTEMPTS, e.status, e.getMessage());

        // 영구 오류(invalid_grant, 형식 오류 등)는 재시도해도 동일하므로 즉시 중단
        if (!e.retryable) {
          break;
        }
        if (attempt < MAX_REFRESH_ATTEMPTS) {
          sleepBeforeRetry(e.retryAfterSeconds, attempt);
        }
      } catch (RuntimeException e) {
        // 네트워크/IO 등 비-HTTP 오류(RestClient 가 ResourceAccessException 등으로 래핑): 일시적일 수 있어 기본 백오프로 재시도
        lastException = e;
        log.warn("토큰 갱신 시도 {}/{} 실패(네트워크/기타): {}", attempt, MAX_REFRESH_ATTEMPTS, e.getMessage());
        if (attempt < MAX_REFRESH_ATTEMPTS) {
          sleepBeforeRetry(null, attempt);
        }
      }
    }

    if (lastException instanceof TokenRefreshException te && !te.retryable) {
      throw new IllegalStateException(
          String.format("토큰 갱신 영구 실패(재시도 불가, 재인증 필요 가능): status=%d %s",
              te.status, te.responseBody), te);
    }
    throw new IllegalStateException("토큰 갱신 " + MAX_REFRESH_ATTEMPTS + "회 시도 모두 실패", lastException);
  }

  /**
   * 토큰 엔드포인트로 1회 요청한다. 비-2xx 응답은 상태/본문/Retry-After/재시도가능여부를 담은
   * {@link TokenRefreshException} 으로 변환하여 호출자가 재시도 정책을 결정하게 한다.
   */
  private ClaudeTokenResponse requestToken(Map<String, String> body) {
    return claudeRestClient.post()
        .uri(TOKEN_ENDPOINT)
        .contentType(MediaType.APPLICATION_JSON)
        .header("user-agent", USER_AGENT)
        .header("anthropic-beta", OAUTH_BETA)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("x-app", "cli")
        .body(body)
        .exchange((req, res) -> {
          String responseBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
          HttpStatusCode status = res.getStatusCode();

          if (status.is2xxSuccessful()) {
            ClaudeTokenResponse tokenResponse = OBJECT_MAPPER.readValue(responseBody, ClaudeTokenResponse.class);
            if (tokenResponse.getAccessToken() == null) {
              throw new TokenRefreshException(status.value(), responseBody, null, false,
                  "토큰 갱신 응답에 access_token이 없습니다");
            }
            return tokenResponse;
          }

          // 429(rate limit)와 5xx 는 일시적 → 재시도 대상. 400 invalid_grant 는 영구 오류 → 재시도 불가.
          boolean retryable = status.value() == 429 || status.is5xxServerError();
          if (status.value() == 400 && responseBody.contains("invalid_grant")) {
            retryable = false;
          }
          Long retryAfter = parseRetryAfter(res.getHeaders().getFirst("Retry-After"));
          throw new TokenRefreshException(status.value(), responseBody, retryAfter, retryable,
              String.format("토큰 갱신 실패: %s %s", status, responseBody));
        });
  }

  /**
   * Retry-After 헤더(초 단위)를 파싱한다. HTTP-date 형식 등 숫자가 아니면 null 을 반환해 기본 백오프를 쓰게 한다.
   */
  private Long parseRetryAfter(String headerValue) {
    if (StringUtils.isBlank(headerValue)) {
      return null;
    }
    try {
      return Math.max(0L, Long.parseLong(headerValue.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 재시도 전 대기. Retry-After 가 있으면 그 값을(상한 캡 적용), 없으면 지수 백오프 + 지터를 사용한다.
   */
  private void sleepBeforeRetry(Long retryAfterSeconds, int attempt) {
    long delayMillis;
    if (retryAfterSeconds != null) {
      delayMillis = Math.min(retryAfterSeconds * 1000L, MAX_RETRY_BACKOFF_MILLIS);
    } else {
      long base = Math.min(1000L * (1L << (attempt - 1)), MAX_RETRY_BACKOFF_MILLIS);
      long jitter = ThreadLocalRandom.current().nextLong(250L);
      delayMillis = base + jitter;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("토큰 갱신 재시도 대기 중 인터럽트", ie);
    }
  }

  private void sendPing(String accountName, String accessToken) {
    Request request = new Request(
        MODEL,
        128,
        List.of(new ClaudeMessage.SystemBlock("text", SYSTEM_PROMPT)),
        List.of(new Message("user", "ping"))
    );

    claudeRestClient.post()
        .uri("/v1/messages")
        .header("Authorization", "Bearer " + accessToken)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("anthropic-beta", ANTHROPIC_BETA)
        .header("user-agent", USER_AGENT)
        .header("x-app", "cli")
        .header("x-anthropic-billing-header", BILLING_HEADER)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange((req, res) -> {
          if (res.getStatusCode().is2xxSuccessful()) {
            log.info("[{}] ping 성공", accountName);
          } else if (res.getStatusCode().value() == 429) {
            log.warn("[{}] ping 429 rate_limit — 현재 사용량 한도 초과 상태 (토큰 갱신은 정상)", accountName);
          } else {
            String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException(
                String.format("[%s] ping 실패: %s %s", accountName, res.getStatusCode(), body));
          }
          return null;
        });
  }

  private void updateTokenAttributes(Long nodeId, Map<String, Object> currentAttributes,
      String accessToken, String refreshToken, long expiresAt) {
    Map<String, Object> updatedAttributes = new HashMap<>(currentAttributes);
    updatedAttributes.put(ATTR_ACCESS_TOKEN, accessToken);
    updatedAttributes.put(ATTR_REFRESH_TOKEN, refreshToken);
    updatedAttributes.put(ATTR_EXPIRES_AT, expiresAt);

    MasterCodeUpdate update = MasterCodeUpdate.builder()
        .attributes(updatedAttributes)
        .build();

    masterCodeService.updateNode(nodeId, update);
  }

  /**
   * 토큰 갱신 실패를 상태코드/응답본문/Retry-After/재시도가능여부와 함께 전달하기 위한 내부 예외.
   */
  private static class TokenRefreshException extends RuntimeException {

    private final int status;
    private final String responseBody;
    private final Long retryAfterSeconds;
    private final boolean retryable;

    TokenRefreshException(int status, String responseBody, Long retryAfterSeconds, boolean retryable, String message) {
      super(message);
      this.status = status;
      this.responseBody = responseBody;
      this.retryAfterSeconds = retryAfterSeconds;
      this.retryable = retryable;
    }
  }
}
