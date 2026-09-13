package kr.hvy.blog.modules.advisor.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import kr.hvy.blog.modules.advisor.application.chat.SlackChatRouter.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 필터 6단계 진리표와 라우팅(중복·실행기 포화·예외 격리). Bolt 이벤트 객체 없이 {@link IncomingQuestion} 으로 조립한다.
 */
class SlackChatRouterTest {

  static final String CHANNEL = "C0ADVISOR";
  static final String BOT_USER = "U0BOT";
  static final List<String> ALLOWED = List.of("U0OWNER");

  private AdvisorChatProperties properties;
  private ChatEventDeduplicator deduplicator;
  private AdvisorChatService service;
  private Executor executor;
  private SlackChatRouter router;

  @BeforeEach
  void setUp() {
    properties = mock(AdvisorChatProperties.class);
    when(properties.getChannelId()).thenReturn(CHANNEL);
    when(properties.getAllowedUserIds()).thenReturn(ALLOWED);
    deduplicator = mock(ChatEventDeduplicator.class);
    when(deduplicator.firstSeen(anyString())).thenReturn(true);
    service = mock(AdvisorChatService.class);
    executor = mock(Executor.class);
    router = new SlackChatRouter(properties, deduplicator, service, executor);
  }

  static IncomingQuestion question(String channel, String user, String botId, String text) {
    return new IncomingQuestion("Ev1", channel, user, botId, "1700000000.000100", null, text);
  }

  @Test
  @DisplayName("허용 사용자의 채널 메시지만 통과한다")
  void acceptsAllowedUserInChannel() {
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0OWNER", null, "삼성전자 어때?"), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.ACCEPT);
  }

  @Test
  @DisplayName("다른 채널·봇·사용자 없음·자기 자신·비허용·공백 본문은 각각의 사유로 거부된다")
  void rejectsEachFilter() {
    assertThat(SlackChatRouter.decide(question("C0OTHER", "U0OWNER", null, "q"), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.OTHER_CHANNEL);
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0OWNER", "B0123", "q"), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.BOT_MESSAGE);
    assertThat(SlackChatRouter.decide(question(CHANNEL, null, null, "q"), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.NO_USER);
    assertThat(SlackChatRouter.decide(question(CHANNEL, BOT_USER, null, "q"), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.SELF_MESSAGE);
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0STRANGER", null, "q"), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.USER_NOT_ALLOWED);
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0OWNER", null, "   "), CHANNEL, ALLOWED, BOT_USER)).isEqualTo(Verdict.EMPTY_TEXT);
  }

  @Test
  @DisplayName("채널 ID 가 비어 있거나 허용 목록이 null 이면 아무것도 통과하지 않는다")
  void emptyConfigurationRejectsEverything() {
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0OWNER", null, "q"), "", ALLOWED, BOT_USER)).isEqualTo(Verdict.OTHER_CHANNEL);
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0OWNER", null, "q"), CHANNEL, null, BOT_USER)).isEqualTo(Verdict.USER_NOT_ALLOWED);
  }

  @Test
  @DisplayName("봇 user id 를 아직 모르면(null) 자기 판정은 건너뛰고 허용 목록이 막는다")
  void unknownBotUserIdFallsBackToAllowList() {
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0OWNER", null, "q"), CHANNEL, ALLOWED, null)).isEqualTo(Verdict.ACCEPT);
    assertThat(SlackChatRouter.decide(question(CHANNEL, "U0BOT", null, "q"), CHANNEL, ALLOWED, null)).isEqualTo(Verdict.USER_NOT_ALLOWED);
  }

  @Test
  @DisplayName("통과한 질문은 실행기에 제출된다")
  void acceptedQuestionIsSubmitted() {
    IncomingQuestion q = question(CHANNEL, "U0OWNER", null, "코스피 강세장이야?");
    assertThat(router.route(q, BOT_USER)).isEqualTo(Verdict.ACCEPT);
    verify(executor).execute(any(Runnable.class));
    verify(deduplicator).firstSeen("Ev1");
  }

  @Test
  @DisplayName("거부된 질문은 중복 판정·실행기 어느 쪽에도 닿지 않는다")
  void rejectedQuestionTouchesNothing() {
    assertThat(router.route(question(CHANNEL, "U0STRANGER", null, "q"), BOT_USER)).isEqualTo(Verdict.USER_NOT_ALLOWED);
    verify(deduplicator, never()).firstSeen(anyString());
    verify(executor, never()).execute(any());
  }

  @Test
  @DisplayName("이미 본 event_id 는 DUPLICATE 로 버린다")
  void duplicateEventIsDropped() {
    when(deduplicator.firstSeen("Ev1")).thenReturn(false);
    assertThat(router.route(question(CHANNEL, "U0OWNER", null, "q"), BOT_USER)).isEqualTo(Verdict.DUPLICATE);
    verify(executor, never()).execute(any());
  }

  @Test
  @DisplayName("실행기 큐가 차면 '잠시 후' 안내를 부르고 REJECTED_BUSY")
  void executorSaturationRepliesBusy() {
    doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
    IncomingQuestion q = question(CHANNEL, "U0OWNER", null, "q");
    assertThat(router.route(q, BOT_USER)).isEqualTo(Verdict.REJECTED_BUSY);
    verify(service).replyBusy(q);
  }

  @Test
  @DisplayName("라우팅 중 예외는 밖으로 새지 않는다(ack 가 막히면 재전송 폭주)")
  void exceptionsAreContained() {
    when(deduplicator.firstSeen(anyString())).thenThrow(new IllegalStateException("redis down"));
    assertThat(router.route(question(CHANNEL, "U0OWNER", null, "q"), BOT_USER)).isEqualTo(Verdict.ERROR);
  }

  @Test
  @DisplayName("답글 스레드 ts — 댓글이면 원 스레드, 새 글이면 자기 ts")
  void replyThreadTs() {
    assertThat(new IncomingQuestion("e", CHANNEL, "U", null, "2.0", "1.0", "q").replyThreadTs()).isEqualTo("1.0");
    assertThat(new IncomingQuestion("e", CHANNEL, "U", null, "2.0", null, "q").replyThreadTs()).isEqualTo("2.0");
    assertThat(new IncomingQuestion("e", CHANNEL, "U", null, "2.0", "1.0", "q").isThreadReply()).isTrue();
    assertThat(new IncomingQuestion("e", CHANNEL, "U", null, "2.0", "2.0", "q").isThreadReply()).isFalse();
  }
}
