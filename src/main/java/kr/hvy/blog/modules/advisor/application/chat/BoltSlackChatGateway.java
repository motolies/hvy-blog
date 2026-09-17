package kr.hvy.blog.modules.advisor.application.chat;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.reactions.ReactionsAddResponse;
import com.slack.api.methods.response.reactions.ReactionsRemoveResponse;
import com.slack.api.model.Message;
import com.slack.api.model.block.LayoutBlock;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Slack 공식 SDK {@code MethodsClient} 위의 {@link SlackChatGateway}. hvy-common SlackClient 와 같은 bot 토큰(slack.token)을 쓴다.
 * 토큰이 비어 있어도 빈은 만들어진다 — 그 경우 러너가 연결 자체를 열지 않으므로 여기까지 호출이 오지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class BoltSlackChatGateway implements SlackChatGateway {

  private final MethodsClient client;

  public BoltSlackChatGateway(AdvisorChatProperties properties) {
    this.client = Slack.getInstance().methods(properties.botToken());
  }

  @Override
  public String postReply(String channelId, String threadTs, String fallbackText, List<LayoutBlock> blocks) throws IOException, SlackApiException {
    ChatPostMessageResponse response = client.chatPostMessage(r -> r.channel(channelId).threadTs(threadTs).text(fallbackText).blocks(blocks));
    if (!response.isOk()) {
      throw new IllegalStateException("chat.postMessage 실패: " + response.getError());
    }
    return response.getTs();
  }

  @Override
  public void addReaction(String channelId, String ts, String name) {
    try {
      ReactionsAddResponse response = client.reactionsAdd(r -> r.channel(channelId).timestamp(ts).name(name));
      if (!response.isOk() && !"already_reacted".equals(response.getError())) {
        // 👀 가 안 붙으면 "봇이 못 받았다" 로 오인하기 쉬워 원인(스코프 누락 등)을 운영 레벨로 남긴다
        log.warn("reactions.add 실패(무시): {} {}", name, response.getError());
      }
    } catch (Exception e) {
      log.warn("reactions.add 예외(무시): {} {}", name, e.getMessage());
    }
  }

  @Override
  public void removeReaction(String channelId, String ts, String name) {
    try {
      ReactionsRemoveResponse response = client.reactionsRemove(r -> r.channel(channelId).timestamp(ts).name(name));
      if (!response.isOk() && !"no_reaction".equals(response.getError())) {
        log.debug("reactions.remove 실패(무시): {} {}", name, response.getError());
      }
    } catch (Exception e) {
      log.debug("reactions.remove 예외(무시): {} {}", name, e.getMessage());
    }
  }

  @Override
  public List<Message> threadReplies(String channelId, String threadTs, int limit) throws IOException, SlackApiException {
    ConversationsRepliesResponse response = client.conversationsReplies(r -> r.channel(channelId).ts(threadTs).limit(limit));
    if (!response.isOk()) {
      throw new IllegalStateException("conversations.replies 실패: " + response.getError());
    }
    return response.getMessages() == null ? List.of() : response.getMessages();
  }
}
