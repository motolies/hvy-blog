package kr.hvy.blog.modules.advisor.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.Message;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.repository.jdbc.ChatWriter;
import kr.hvy.common.observability.TraceBoundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 서비스의 수명 전이(RUNNING→SUCCESS/FAILED), 리액션 순서, 스레드 ts, 예외 격리. Slack 은 가짜 게이트웨이, DB 는 Mockito.
 */
class AdvisorChatServiceTest {

  /** 호출을 기록하는 가짜 Slack — postReply 는 설정에 따라 실패시킬 수 있다 */
  static class FakeSlack implements SlackChatGateway {

    final List<String> reactions = new ArrayList<>();
    final List<String> replies = new ArrayList<>();
    final List<List<LayoutBlock>> blocks = new ArrayList<>();
    String failWith;

    @Override
    public String postReply(String channelId, String threadTs, String fallbackText, List<LayoutBlock> b) {
      if (failWith != null) {
        String f = failWith;
        failWith = null;
        throw new IllegalStateException(f);
      }
      replies.add(threadTs + "|" + fallbackText);
      blocks.add(b);
      return "9.0";
    }

    @Override
    public void addReaction(String channelId, String ts, String name) {
      reactions.add("+" + name);
    }

    @Override
    public void removeReaction(String channelId, String ts, String name) {
      reactions.add("-" + name);
    }

    @Override
    public List<Message> threadReplies(String channelId, String threadTs, int limit) {
      return List.of();
    }
  }

  private AdvisorChatProperties properties;
  private ChatWriter writer;
  private FakeSlack slack;
  private ChatAnswerer answerer;
  private AdvisorChatService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    properties = mock(AdvisorChatProperties.class);
    when(properties.getMaxBlocks()).thenReturn(20);
    writer = mock(ChatWriter.class);
    when(writer.insertRunning(any())).thenReturn(Optional.of(7L));
    slack = new FakeSlack();
    answerer = null;
    ObjectProvider<ChatAnswerer> answererProvider = mock(ObjectProvider.class);
    when(answererProvider.getIfAvailable()).thenAnswer(inv -> answerer);
    ObjectProvider<TraceBoundary> traceProvider = mock(ObjectProvider.class);
    when(traceProvider.getIfAvailable()).thenReturn(null);
    service = new AdvisorChatService(properties, writer, slack, answererProvider, traceProvider);
  }

  static IncomingQuestion question(String threadTs) {
    return new IncomingQuestion("Ev1", "C1", "U1", null, "2.000", threadTs, "삼성전자 어때?");
  }

  @Test
  @DisplayName("답변자가 없으면 에코 — 스레드 답글·SUCCESS·리액션 👀→✅")
  void echoPath() {
    service.handle(question(null));
    assertThat(slack.replies).containsExactly("2.000|질문을 받았습니다: 삼성전자 어때?");
    assertThat(slack.reactions).containsExactly("+eyes", "-eyes", "+white_check_mark");
    verify(writer).finishSuccess(eq(7L), any(ChatResult.class), anyLong());
    List<LayoutBlock> blocks = slack.blocks.getFirst();
    assertThat(blocks).hasSize(3);
    assertThat(((MarkdownTextObject) ((SectionBlock) blocks.get(0)).getText()).getText()).isEqualTo("질문을 받았습니다: 삼성전자 어때?");
    assertThat(((MarkdownTextObject) ((ContextBlock) blocks.get(1)).getElements().getFirst()).getText()).startsWith("chat=7 · model=echo · tools: 없음");
  }

  @Test
  @DisplayName("댓글이면 원 스레드에 답한다")
  void repliesIntoExistingThread() {
    service.handle(question("1.000"));
    assertThat(slack.replies.getFirst()).startsWith("1.000|");
  }

  @Test
  @DisplayName("답변자 빈이 있으면 그 결과를 쓰고 사용량이 감사에 남는다")
  void usesAnswererWhenPresent() {
    answerer = q -> ChatResult.builder().answer("**코스피** 강세").model("gpt-x").promptVersion("chat-v1").promptTokens(120).completionTokens(30)
        .toolCalls(List.of("marketTrend")).build();
    service.handle(question(null));
    assertThat(slack.replies).containsExactly("2.000|코스피 강세");
    assertThat(((MarkdownTextObject) ((ContextBlock) slack.blocks.getFirst().get(1)).getElements().getFirst()).getText())
        .isEqualTo("chat=7 · model=gpt-x · tools: marketTrend · in 120 / out 30 tok");
  }

  @Test
  @DisplayName("Slack 답글 실패 → FAILED 기록 + 실패 안내 답글 + ⚠, 예외는 밖으로 나오지 않는다")
  void postFailureIsRecorded() {
    slack.failWith = "chat.postMessage 실패: channel_not_found";
    service.handle(question(null));
    verify(writer).finishFailed(eq(7L), anyString(), anyLong());
    verify(writer, never()).finishSuccess(anyLong(), any(), anyLong());
    assertThat(slack.replies).hasSize(1);
    assertThat(slack.replies.getFirst()).contains("답변을 만들지 못했습니다");
    assertThat(slack.reactions).containsExactly("+eyes", "-eyes", "+warning");
  }

  @Test
  @DisplayName("답변자가 예외를 던져도 같은 실패 경로")
  void answererFailureIsRecorded() {
    answerer = q -> {
      throw new IllegalStateException("openai 503");
    };
    service.handle(question(null));
    verify(writer).finishFailed(eq(7L), anyString(), anyLong());
    assertThat(slack.replies.getFirst()).contains("openai 503");
  }

  @Test
  @DisplayName("DB 유니크 충돌(재전송)이면 아무것도 하지 않는다")
  void duplicateInsertDoesNothing() {
    when(writer.insertRunning(any())).thenReturn(Optional.empty());
    service.handle(question(null));
    assertThat(slack.replies).isEmpty();
    assertThat(slack.reactions).isEmpty();
  }

  @Test
  @DisplayName("감사 INSERT 자체가 실패하면 처리하지 않고 예외도 내지 않는다")
  void insertFailureIsContained() {
    when(writer.insertRunning(any())).thenThrow(new IllegalStateException("db down"));
    service.handle(question(null));
    assertThat(slack.replies).isEmpty();
  }

  @Test
  @DisplayName("replyBusy 는 안내 한 줄만 올리고 실패를 삼킨다")
  void replyBusy() {
    service.replyBusy(question("1.000"));
    assertThat(slack.replies).containsExactly("1.000|" + AdvisorChatService.BUSY_TEXT);
    slack.failWith = "down";
    service.replyBusy(question("1.000"));
    assertThat(slack.replies).hasSize(1);
  }
}
