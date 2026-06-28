package kr.hvy.blog.modules.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.claude.client.dto.ClaudeMessage;
import kr.hvy.blog.modules.claude.client.dto.ClaudeMessage.Message;
import kr.hvy.blog.modules.claude.client.dto.ClaudeMessage.Request;
import kr.hvy.blog.modules.claude.client.dto.ClaudeTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Claude Code 토큰 갱신 + ping 수동 진단 테스트 (Spring 컨텍스트 미사용).
 *
 * <p>실제 토큰을 코드에 직접 넣어 실행하는 <b>수동 도구</b>다. {@code ClaudeCodeRefreshService} 의
 * 요청을 1:1 로 재현하되, DB / MasterCode / Notify 빈에 의존하지 않도록 RestClient 를 직접 만들어
 * 절대 URL 로 호출한다. {@code .exchange()} 는 상태 핸들러를 우회하므로 404 같은 비-2xx 응답도
 * 예외 없이 status + body 를 그대로 받아 원인을 판별할 수 있다.
 *
 * <p>사용법:
 * <ol>
 *   <li>아래 ACCESS_TOKEN / REFRESH_TOKEN 에 실제 토큰 문자열을 붙여넣는다.
 *       <ul>
 *         <li>{@link #refreshThenPing()} : REFRESH_TOKEN 만 있으면 됨(토큰 갱신 → 그 토큰으로 ping).</li>
 *         <li>{@link #pingOnly()} : ACCESS_TOKEN 만 있으면 됨.</li>
 *       </ul>
 *   </li>
 *   <li>IDE 에서 메서드 실행, 또는
 *       {@code ./gradlew :hvy-blog:test --tests "kr.hvy.blog.modules.claude.ClaudeCodePingManualTest"}</li>
 *   <li>출력된 status / body 로 원인 판별. {@link #MODEL} 을 {@code claude-sonnet-4-20250514}(은퇴) 로
 *       바꾸면 404 not_found_error 가 재현되어 "모델이 원인"임을 증명할 수 있다.</li>
 * </ol>
 *
 * <p><b>주의:</b> 토큰을 채운 채로 커밋하지 말 것(빈 문자열 상태로만 커밋).
 */
@Slf4j
class ClaudeCodePingManualTest {

  // ── 여기에 실제 토큰을 붙여넣으세요(커밋 전 다시 비울 것) ───────────────
  private static final String ACCESS_TOKEN = "";
  private static final String REFRESH_TOKEN = "";
  // ───────────────────────────────────────────────────────────────────

  private static final String TOKEN_ENDPOINT = "https://platform.claude.com/v1/oauth/token";
  private static final String MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages?beta=true";
  private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final String OAUTH_BETA = "oauth-2025-04-20";
  private static final String ANTHROPIC_BETA = "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,prompt-caching-scope-2026-01-05";
  private static final String CLI_VERSION = "2.1.195";
  private static final String USER_AGENT = "claude-cli/" + CLI_VERSION + " (external, cli)";
  private static final String SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude.";

  // 모델만 바꿔 즉석 비교 가능:
  //   현재 유효:      claude-haiku-4-5 / claude-sonnet-4-6 / claude-opus-4-8
  //   은퇴(404 재현): claude-sonnet-4-20250514
  private static final String MODEL = "claude-haiku-4-5";
  private static final String BILLING_HEADER = "cc_version=" + CLI_VERSION + "." + MODEL + "; cc_entrypoint=cli; cch=00000;";

  private final RestClient client = RestClient.builder().build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 토큰 갱신 → 새 access_token 으로 ping 까지 end-to-end 재현. */
  @Test
  void refreshThenPing() throws Exception {
    Assumptions.assumeTrue(StringUtils.isNotBlank(REFRESH_TOKEN), "REFRESH_TOKEN 미입력 → skip");

    // 1) OAuth refresh_token 그랜트로 access_token 갱신
    String tokenBody = requestTokenRefresh(REFRESH_TOKEN);
    ClaudeTokenResponse token = objectMapper.readValue(tokenBody, ClaudeTokenResponse.class);
    String accessToken = token.getAccessToken();
    log.info("[TOKEN] access_token 확보={}, expires_in={}s, refresh_token 회전={}",
        StringUtils.isNotBlank(accessToken), token.getExpiresIn(), StringUtils.isNotBlank(token.getRefreshToken()));

    Assumptions.assumeTrue(StringUtils.isNotBlank(accessToken), "토큰 갱신 응답에 access_token 없음 → ping 생략");

    // 2) 그 access_token 으로 ping
    sendPing(accessToken);
  }

  /** 이미 가진 access_token 만으로 ping 만 테스트. */
  @Test
  void pingOnly() {
    Assumptions.assumeTrue(StringUtils.isNotBlank(ACCESS_TOKEN), "ACCESS_TOKEN 미입력 → skip");
    sendPing(ACCESS_TOKEN);
  }

  /** 토큰 갱신 1회: status + 전체 body 를 출력하고 body 문자열을 반환한다. */
  private String requestTokenRefresh(String refreshToken) {
    Map<String, String> body = Map.of(
        "grant_type", "refresh_token",
        "refresh_token", refreshToken,
        "client_id", CLIENT_ID
    );

    return client.post()
        .uri(TOKEN_ENDPOINT)
        .contentType(MediaType.APPLICATION_JSON)
        .header("user-agent", USER_AGENT)
        .header("anthropic-beta", OAUTH_BETA)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("x-app", "cli")
        .body(body)
        .exchange((req, res) -> {
          String responseBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[TOKEN] status={}\nbody={}", res.getStatusCode(), responseBody);
          return responseBody;
        });
  }

  /** ping 1회: status + 전체 body 를 출력한다(비-2xx 도 예외 없이 그대로 출력). */
  private void sendPing(String accessToken) {
    Request request = new Request(
        MODEL,
        128,
        List.of(new ClaudeMessage.SystemBlock("text", SYSTEM_PROMPT)),
        List.of(new Message("user", "ping"))
    );

    client.post()
        .uri(MESSAGES_ENDPOINT)
        .header("Authorization", "Bearer " + accessToken)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("anthropic-beta", ANTHROPIC_BETA)
        .header("user-agent", USER_AGENT)
        .header("x-app", "cli")
        .header("x-anthropic-billing-header", BILLING_HEADER)
        .header("X-Stainless-Lang", "js")
        .header("X-Stainless-Package-Version", CLI_VERSION)
        .header("X-Stainless-OS", "Linux")
        .header("X-Stainless-Arch", "x64")
        .header("X-Stainless-Runtime", "node")
        .header("X-Stainless-Runtime-Version", "v22.0.0")
        .header("X-Stainless-Retry-Count", "0")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange((req, res) -> {
          String responseBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
          log.info("[PING] model={} status={}\nbody={}", MODEL, res.getStatusCode(), responseBody);
          if (res.getStatusCode().value() == 404 || responseBody.contains("not_found_error")) {
            log.warn("[PING] 모델 '{}' 이(가) 유효하지 않습니다(은퇴/오타 가능). MODEL 을 현재 유효한 모델로 변경하세요.", MODEL);
          }
          return null;
        });
  }
}
