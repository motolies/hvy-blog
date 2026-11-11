package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 전달 여부 계약 — {@code alert}·{@code publish} 는 "Slack 이 메시지를 받았는가" 를 돌려주고 예외를 던지지 않는다.
 * LlmProviderGate 는 alert 의 값으로 그날의 알림을 다시 보낼지 정하고, 잡은 publish 의 값으로 "발행됨" 을 기록할지 정한다.
 * Notify(hvy-common 0.4.1)가 전송 결과를 돌려주므로 Slack 의 거부(ok=false)도 여기서 false 로 보인다.
 */
@DisplayName("AdvisorNotifier - Slack 이 받았는지를 돌려주고 예외는 던지지 않는다")
class AdvisorNotifierTest {

  /** 블록이 없는 최소 메시지 */
  private static SlackMessage message() {
    return new SlackMessage() {
      @Override
      public String getChannel() {
        return SlackChannel.NOTIFY.getChannel();
      }

      @Override
      public boolean isNotify() {
        return false;
      }

      @Override
      public String getFallbackText() {
        return "보고";
      }

      @Override
      public List<LayoutBlock> toBlocks() {
        return List.of();
      }
    };
  }

  @Test
  @DisplayName("alert: Slack 이 받으면 true — error=true 는 #hvy-error + 멘션, false 는 #hvy-notify")
  void alert_전달되면_true() {
    Notify notify = mock(Notify.class);
    when(notify.sendMessage(any(NotifyRequest.class))).thenReturn(true);
    AdvisorNotifier notifier = new AdvisorNotifier(Optional.of(notify));

    assertThat(notifier.alert("키 오류", true)).isTrue();
    assertThat(notifier.alert("소진", false)).isTrue();

    ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
    verify(notify, times(2)).sendMessage(captor.capture());
    assertThat(captor.getAllValues()).extracting(NotifyRequest::getChannel)
        .containsExactly(SlackChannel.ERROR.getChannel(), SlackChannel.NOTIFY.getChannel());
    assertThat(captor.getAllValues()).extracting(NotifyRequest::getMessage).containsExactly("키 오류", "소진");
    assertThat(captor.getAllValues()).extracting(NotifyRequest::isNotify).containsExactly(true, false);
  }

  @Test
  @DisplayName("alert: Slack 이 받지 않았으면(전송 실패·ok=false 거부) false — 게이트가 다음 실패 때 다시 알린다")
  void alert_전달안되면_false() {
    Notify notify = mock(Notify.class);
    when(notify.sendMessage(any(NotifyRequest.class))).thenReturn(false);

    assertThat(new AdvisorNotifier(Optional.of(notify)).alert("키 오류", true)).isFalse();
  }

  @Test
  @DisplayName("alert: Notify 빈이 없으면 false")
  void alert_빈없으면_false() {
    assertThat(new AdvisorNotifier(Optional.empty()).alert("키 오류", true)).isFalse();
  }

  @Test
  @DisplayName("alert: 전송이 예외로 끝나면 삼키고 false")
  void alert_예외면_false() {
    Notify notify = mock(Notify.class);
    when(notify.sendMessage(any(NotifyRequest.class))).thenThrow(new IllegalStateException("slack down"));

    assertThat(new AdvisorNotifier(Optional.of(notify)).alert("키 오류", true)).isFalse();
  }

  @Test
  @DisplayName("publish: Slack 이 받았을 때만 true — 블록을 거부하면(invalid_blocks) false 라 잡이 '발행됨' 을 기록하지 않는다")
  void publish_전송결과를_따른다() {
    Notify notify = mock(Notify.class);
    when(notify.sendMessage(any(SlackMessage.class))).thenReturn(true, false);
    AdvisorNotifier notifier = new AdvisorNotifier(Optional.of(notify));

    assertThat(notifier.publish(message())).isTrue();
    assertThat(notifier.publish(message())).as("Slack 이 거부").isFalse();
  }

  @Test
  @DisplayName("publish: Notify 빈이 없거나 전송이 예외로 끝나면 false")
  void publish_빈없음_예외는_false() {
    Notify notify = mock(Notify.class);
    when(notify.sendMessage(any(SlackMessage.class))).thenThrow(new IllegalStateException("블록 조립 실패"));

    assertThat(new AdvisorNotifier(Optional.empty()).publish(message())).isFalse();
    assertThat(new AdvisorNotifier(Optional.of(notify)).publish(message())).isFalse();
  }
}
