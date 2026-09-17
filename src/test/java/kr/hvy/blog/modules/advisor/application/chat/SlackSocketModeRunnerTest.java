package kr.hvy.blog.modules.advisor.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import kr.hvy.blog.modules.advisor.application.service.AdvisorNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * hello 의 연결 수 판독과 close 코드 판정. 실제 WebSocket 없이 순수 함수와 리스너 메서드를 직접 부른다.
 */
class SlackSocketModeRunnerTest {

  /** Slack 이 보내는 형태 그대로의 압축 JSON */
  static final String HELLO_4 = "{\"type\":\"hello\",\"num_connections\":4,\"debug_info\":{\"host\":\"applink-10\",\"build_number\":25,"
      + "\"approximate_connection_time\":18060},\"connection_info\":{\"app_id\":\"A06E6JF87BK\"}}";
  static final String HELLO_1 = HELLO_4.replace("\"num_connections\":4", "\"num_connections\":1");
  /** 본문에 hello 라는 단어가 들어간 일반 이벤트 — 문자열 선별은 통과하지만 type 검사에서 걸러져야 한다 */
  static final String EVENT_WITH_HELLO_TEXT = "{\"type\":\"events_api\",\"envelope_id\":\"e1\",\"payload\":{\"event\":{\"type\":\"message\",\"text\":\"hello\"}}}";

  private AdvisorNotifier notifier;
  private SlackSocketModeRunner runner;

  @BeforeEach
  void setUp() {
    notifier = mock(AdvisorNotifier.class);
    runner = new SlackSocketModeRunner(mock(AdvisorChatProperties.class), mock(SlackChatRouter.class), notifier, mock(RedissonClient.class));
  }

  @Test
  @DisplayName("hello 에서 num_connections 를 읽는다")
  void parseHello() {
    assertThat(SlackSocketModeRunner.parseHelloConnections(HELLO_4)).isEqualTo(4);
    assertThat(SlackSocketModeRunner.parseHelloConnections(HELLO_1)).isEqualTo(1);
  }

  @Test
  @DisplayName("hello 가 아니거나 깨진 원문은 null")
  void parseNonHello() {
    assertThat(SlackSocketModeRunner.parseHelloConnections(null)).isNull();
    assertThat(SlackSocketModeRunner.parseHelloConnections("")).isNull();
    assertThat(SlackSocketModeRunner.parseHelloConnections(EVENT_WITH_HELLO_TEXT)).isNull();
    assertThat(SlackSocketModeRunner.parseHelloConnections("{\"type\":\"hello\"}")).isNull();
    assertThat(SlackSocketModeRunner.parseHelloConnections("{\"type\":\"hello\",\"num_connections\":")).isNull();
    assertThat(SlackSocketModeRunner.parseHelloConnections("[\"hello\"]")).isNull();
  }

  @Test
  @DisplayName("close 판정 — 실행 중의 비정상 코드만 경보 대상")
  void abnormalClose() {
    assertThat(SlackSocketModeRunner.isAbnormalClose(1000, true)).isFalse();
    assertThat(SlackSocketModeRunner.isAbnormalClose(1006, true)).isTrue();
    assertThat(SlackSocketModeRunner.isAbnormalClose(null, true)).isTrue();
    assertThat(SlackSocketModeRunner.isAbnormalClose(1006, false)).isFalse();
    assertThat(SlackSocketModeRunner.isAbnormalClose(1000, false)).isFalse();
  }

  @Test
  @DisplayName("기동 후 첫 hello 가 2 이상이면 경보 1회 — 이후 hello 는 로그만")
  void alertOnlyOnFirstHello() {
    runner.onRawMessage(HELLO_4);
    runner.onRawMessage(HELLO_4);

    verify(notifier, times(1)).alert(contains("4개"), anyBoolean());
  }

  @Test
  @DisplayName("첫 hello 가 1 이면 경보 없음 — 나중에 늘어나도 refresh 겹침일 수 있어 로그만")
  void noAlertWhenSingleConnection() {
    runner.onRawMessage(HELLO_1);
    runner.onRawMessage(HELLO_4);
    runner.onRawMessage(EVENT_WITH_HELLO_TEXT);

    verify(notifier, never()).alert(anyString(), anyBoolean());
  }

  @Test
  @DisplayName("실행 중이 아닐 때의 close 와 정상 종료 1000 은 경보하지 않는다")
  void normalCloseDoesNotAlert() {
    runner.onClose(1000, "");
    runner.onClose(1006, "Closed abnormally.");

    verify(notifier, never()).alert(anyString(), anyBoolean());
  }
}
