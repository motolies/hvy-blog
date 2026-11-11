package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 임의 경보({@code alert})의 전달 여부 계약 — LlmProviderGate 가 이 값으로 "오늘 알림을 보냈는가" 를 정한다.
 * Slack API·네트워크 오류는 Notify 구현(hvy-common SlackClient)이 삼키므로 여기서 볼 수 있는 실패는 빈 없음과 런타임 예외뿐이다.
 */
@DisplayName("AdvisorNotifier.alert - 전달 여부를 돌려주고 예외는 던지지 않는다")
class AdvisorNotifierTest {

  @Test
  @DisplayName("전송을 마치면 true — error=true 는 #hvy-error + 멘션, false 는 #hvy-notify")
  void 전송하면_true() {
    Notify notify = mock(Notify.class);
    AdvisorNotifier notifier = new AdvisorNotifier(Optional.of(notify));

    assertThat(notifier.alert("키 오류", true)).isTrue();
    assertThat(notifier.alert("소진", false)).isTrue();

    ArgumentCaptor<NotifyRequest> captor = ArgumentCaptor.forClass(NotifyRequest.class);
    verify(notify, org.mockito.Mockito.times(2)).sendMessage(captor.capture());
    assertThat(captor.getAllValues()).extracting(NotifyRequest::getChannel)
        .containsExactly(SlackChannel.ERROR.getChannel(), SlackChannel.NOTIFY.getChannel());
    assertThat(captor.getAllValues()).extracting(NotifyRequest::getMessage).containsExactly("키 오류", "소진");
  }

  @Test
  @DisplayName("Notify 빈이 없으면 false — 게이트가 다음 실패 때 다시 알린다")
  void 빈없으면_false() {
    assertThat(new AdvisorNotifier(Optional.empty()).alert("키 오류", true)).isFalse();
  }

  @Test
  @DisplayName("전송이 예외로 끝나면 삼키고 false")
  void 예외면_false() {
    Notify notify = mock(Notify.class);
    doThrow(new IllegalStateException("slack down")).when(notify).sendMessage(any(NotifyRequest.class));

    assertThat(new AdvisorNotifier(Optional.of(notify)).alert("키 오류", true)).isFalse();
  }
}
