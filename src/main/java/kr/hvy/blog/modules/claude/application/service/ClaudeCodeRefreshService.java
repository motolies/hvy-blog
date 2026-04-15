package kr.hvy.blog.modules.claude.application.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private static final String ANTHROPIC_BETA = "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,prompt-caching-scope-2026-01-05";
  private static final String CLI_VERSION = "2.1.101";
  private static final String USER_AGENT = "claude-cli/" + CLI_VERSION + " (external, cli)";
  private static final String BILLING_HEADER = "cc_version=" + CLI_VERSION + ".claude-sonnet-4-20250514; cc_entrypoint=cli; cch=00000;";
  private static final String SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude.";
  private static final String MODEL = "claude-sonnet-4-20250514";

  private final RestClient claudeRestClient;
  private final MasterCodeService masterCodeService;
  private final Notify notify;

  public ClaudeCodeRefreshService(
      @Qualifier("claudeRestClient") RestClient claudeRestClient,
      MasterCodeService masterCodeService,
      Notify notify
  ) {
    this.claudeRestClient = claudeRestClient;
    this.masterCodeService = masterCodeService;
    this.notify = notify;
  }

  public void refreshAndPing() {
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

  private void refreshAccount(MasterCodeResponse account) {
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

      // 갱신된 토큰 정보를 MasterCode에 저장
      long expiresAt = System.currentTimeMillis() + (tokenResponse.getExpiresIn() * 1000);
      String newRefreshToken = StringUtils.isNotBlank(tokenResponse.getRefreshToken())
          ? tokenResponse.getRefreshToken() : refreshToken;

      updateTokenAttributes(account.getId(), attrs, accessToken, newRefreshToken, expiresAt);
    } else {
      log.info("[{}] accessToken 유효 (만료까지 {}분), 갱신 생략", accountName, remainingMinutes(attrs));
    }

    // 2. ping 대화 수행 (사용량 타이머 리셋)
    sendPing(accountName, accessToken);
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

  private ClaudeTokenResponse refreshAccessToken(String refreshToken) {
    Map<String, String> body = Map.of(
        "grant_type", "refresh_token",
        "refresh_token", refreshToken,
        "client_id", CLIENT_ID
    );

    int maxAttempts = 3;
    Exception lastException = null;

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        ClaudeTokenResponse tokenResponse = claudeRestClient.post()
            .uri(TOKEN_ENDPOINT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange((req, res) -> {
              String responseBody = new String(res.getBody().readAllBytes());
              if (!res.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                    String.format("토큰 갱신 실패: %s %s", res.getStatusCode(), responseBody));
              }
              return new com.fasterxml.jackson.databind.ObjectMapper()
                  .readValue(responseBody, ClaudeTokenResponse.class);
            });
        if (tokenResponse.getAccessToken() == null) {
          throw new IllegalStateException("토큰 갱신 응답에 access_token이 없습니다");
        }
        return tokenResponse;
      } catch (Exception e) {
        lastException = e;
        log.warn("토큰 갱신 시도 {}/{} 실패: {}", attempt + 1, maxAttempts, e.getMessage());
        if (attempt < maxAttempts - 1) {
          try {
            Thread.sleep(1000L * (attempt + 1));
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("토큰 갱신 중 인터럽트", ie);
          }
        }
      }
    }

    throw new IllegalStateException("토큰 갱신 " + maxAttempts + "회 시도 모두 실패", lastException);
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
        .header("anthropic-version", "2023-06-01")
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
            String body = new String(res.getBody().readAllBytes());
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
}
