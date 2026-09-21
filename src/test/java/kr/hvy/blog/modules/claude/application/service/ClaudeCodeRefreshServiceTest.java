package kr.hvy.blog.modules.claude.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeUpdate;
import kr.hvy.blog.modules.admin.application.service.MasterCodeService;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 정적 토큰(claude setup-token) 모드와 기존 리프레시 모드의 분기·만료 점검·알림을 검증한다.
 * <p>
 * RestClient 는 MockRestServiceServer 로, MasterCode/Slack 은 mock 으로 대체하는 순수 단위 테스트다.
 * 시각은 현재 시각 기준 상대값(±N일)으로 만들어 Clock 주입 없이 검증한다.
 */
class ClaudeCodeRefreshServiceTest {

  private static final String ACCOUNT_ID = "0ABCDEFGHJKMN";
  private static final String ACCOUNT_CODE = "MAIN";
  private static final String PING_URL = "https://api.anthropic.com/v1/messages?beta=true";
  private static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
  private static final long DAY = 86_400_000L;

  private MockRestServiceServer server;
  private final MasterCodeService masterCodeService = mock(MasterCodeService.class);
  private final Notify notify = mock(Notify.class);
  private ClaudeCodeRefreshService service;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.anthropic.com");
    server = MockRestServiceServer.bindTo(builder).build();
    service = new ClaudeCodeRefreshService(builder.build(), masterCodeService, notify);
  }

  /** CLAUDE 루트 하위 계정 1건을 캐시 조회·DB 재조회 양쪽에서 돌려주도록 스텁한다. */
  private void givenAccount(String accessToken, String refreshToken, Object expiresAt) {
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("accessToken", accessToken);
    attrs.put("refreshToken", refreshToken);
    if (expiresAt != null) {
      attrs.put("expiresAt", expiresAt);
    }
    MasterCodeResponse account = MasterCodeResponse.builder()
        .id(ACCOUNT_ID).code(ACCOUNT_CODE).attributes(attrs).build();
    given(masterCodeService.getChildrenByRootCode("CLAUDE")).willReturn(List.of(account));
    given(masterCodeService.getNode(ACCOUNT_ID)).willReturn(account);
  }

  /** 주어진 accessToken 으로 ping 이 count 회 나가는 것을 기대한다. */
  private void expectPing(ExpectedCount count, String accessToken) {
    server.expect(count, requestTo(PING_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
  }

  private NotifyRequest capturedNotify() {
    ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
    verify(notify).sendMessage(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("정적 토큰이 충분히 남았으면 갱신·저장·알림 없이 ping 만 보낸다")
  void 정적토큰_정상() {
    givenAccount("static-token", "", System.currentTimeMillis() + 200 * DAY);
    expectPing(ExpectedCount.once(), "static-token");

    service.refreshAndPing();

    server.verify();
    verify(masterCodeService, never()).updateNode(anyString(), any(MasterCodeUpdate.class));
    verify(notify, never()).sendMessage(any(NotifyRequest.class));
  }

  @Test
  @DisplayName("만료 14일 이내면 NOTIFY 로 멘션 없이 D-day 를 알리고, 같은 날 재실행해도 한 번만 보낸다 — 문자열 expiresAt 도 해석")
  void 정적토큰_만료임박_하루1회() {
    givenAccount("static-token", null, String.valueOf(System.currentTimeMillis() + 10 * DAY));
    expectPing(ExpectedCount.twice(), "static-token");

    service.refreshAndPing();
    service.refreshAndPing();

    server.verify();
    NotifyRequest request = capturedNotify(); // times(1) 검증 포함
    assertThat(request.getChannel()).isEqualTo(SlackChannel.NOTIFY.getChannel());
    assertThat(request.isNotify()).isFalse();
    assertThat(request.getMessage()).contains("[" + ACCOUNT_CODE + "]").contains("D-10").contains("claude setup-token");
  }

  @Test
  @DisplayName("정적 토큰이 만료됐으면 ping 없이 실패 계정으로 집계하고 ERROR 로 재발급을 안내한다")
  void 정적토큰_만료() {
    givenAccount("static-token", "", System.currentTimeMillis() - DAY);

    assertThatThrownBy(service::refreshAndPing)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ACCOUNT_CODE);

    server.verify(); // 기대한 요청 없음 = ping 미발송
    NotifyRequest request = capturedNotify();
    assertThat(request.getChannel()).isEqualTo(SlackChannel.ERROR.getChannel());
    assertThat(request.getException()).hasMessageContaining("setup-token 만료");
  }

  @Test
  @DisplayName("expiresAt 이 숫자가 아니면(시드 플레이스홀더) 만료 점검만 건너뛰고 ping 은 보낸다")
  void 정적토큰_expiresAt_해석불가() {
    givenAccount("static-token", "", "<<expiresAt>>");
    expectPing(ExpectedCount.once(), "static-token");

    service.refreshAndPing();

    server.verify();
    verify(notify, never()).sendMessage(any(NotifyRequest.class));
  }

  @Test
  @DisplayName("refreshToken 과 accessToken 이 모두 비면 실패 계정으로 집계한다")
  void 토큰_모두_없음() {
    givenAccount("", "", null);

    assertThatThrownBy(service::refreshAndPing)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ACCOUNT_CODE);

    server.verify();
    assertThat(capturedNotify().getException()).hasMessageContaining("모두 비어있습니다");
  }

  @Test
  @DisplayName("회귀: 리프레시 모드는 만료된 accessToken 을 갱신하고 회전된 refreshToken 을 저장한 뒤 새 토큰으로 ping 한다")
  void 리프레시모드_회귀() {
    givenAccount("old-access", "old-refresh", System.currentTimeMillis() - 60_000L);
    server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.grant_type").value("refresh_token"))
        .andExpect(jsonPath("$.refresh_token").value("old-refresh"))
        .andRespond(withSuccess("""
            {"access_token":"new-access","token_type":"Bearer","expires_in":28800,"refresh_token":"new-refresh"}
            """, MediaType.APPLICATION_JSON));
    expectPing(ExpectedCount.once(), "new-access");

    service.refreshAndPing();

    server.verify();
    ArgumentCaptor<MasterCodeUpdate> captor = ArgumentCaptor.forClass(MasterCodeUpdate.class);
    verify(masterCodeService).updateNode(eq(ACCOUNT_ID), captor.capture());
    Map<String, Object> saved = captor.getValue().getAttributes();
    assertThat(saved).containsEntry("accessToken", "new-access").containsEntry("refreshToken", "new-refresh");
    assertThat((Long) saved.get("expiresAt")).isGreaterThan(System.currentTimeMillis());
    verify(notify, never()).sendMessage(any(NotifyRequest.class));
  }
}
