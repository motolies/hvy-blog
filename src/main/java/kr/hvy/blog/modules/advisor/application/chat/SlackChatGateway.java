package kr.hvy.blog.modules.advisor.application.chat;

import com.slack.api.methods.SlackApiException;
import com.slack.api.model.Message;
import com.slack.api.model.block.LayoutBlock;
import java.io.IOException;
import java.util.List;

/**
 * 채팅 봇이 쓰는 Slack Web API 네 가지. hvy-common {@code Notify} 를 쓰지 않는 이유는 thread_ts 를 실을 수 없고 응답(ts)을 버리기 때문이다.
 * 인터페이스로 둔 것은 서비스 단위 테스트에서 가짜로 바꾸기 위해서다.
 */
public interface SlackChatGateway {

  /**
   * 스레드 답글을 올린다. 실패는 예외로 — 서비스가 FAILED 로 기록해야 한다.
   *
   * @return 올린 메시지의 ts
   */
  String postReply(String channelId, String threadTs, String fallbackText, List<LayoutBlock> blocks) throws IOException, SlackApiException;

  /**
   * 리액션을 붙인다. 실패는 삼킨다(답변은 이미 갔는데 리액션 때문에 실패로 기록되면 안 된다).
   */
  void addReaction(String channelId, String ts, String name);

  /**
   * 리액션을 뗀다. 실패는 삼킨다.
   */
  void removeReaction(String channelId, String ts, String name);

  /**
   * 스레드의 메시지들(루트 포함, 오래된 것부터). 실패는 예외로.
   */
  List<Message> threadReplies(String channelId, String threadTs, int limit) throws IOException, SlackApiException;
}
