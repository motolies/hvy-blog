package kr.hvy.blog.modules.advisor.application.chat;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageThreadBroadcastEvent;
import com.slack.api.socket_mode.SocketModeClient;
import java.time.Duration;
import kr.hvy.blog.modules.advisor.application.service.AdvisorNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Slack Socket Mode 연결의 생명주기. 컨텍스트가 완전히 준비된 뒤(가장 늦은 phase) WebSocket 을 열고, 종료 시 가장 먼저 닫는다.
 * <p>
 * {@code @PostConstruct} 로 열면 다른 빈이 준비되기 전에 첫 이벤트가 미완성 컨텍스트를 때릴 수 있어 {@link SmartLifecycle} 을 쓴다.
 * 설정이 비어 있으면({@link AdvisorChatProperties#isRunnable()} false) 시작하지 않고 WARN 만 남긴다 — 기동 실패보다 기능 단위 실패가 낫다는
 * AdvisorAiConfig 의 계약과 같다. 연결 시작이 실패해도 예외를 밖으로 내지 않는다.
 * <p>
 * 재연결은 SDK 의 SocketModeClient 가 담당한다(핑 모니터 + 자동 재접속). 끊긴 동안 도착한 메시지는 Slack 이 재전송하지 않으므로 유실되며,
 * close/error 리스너가 WARN 과 #hvy-notify 경보(쿨다운 당 1회)를 남긴다. 평시 연결 상태는 Slack 사이드바의 봇 온라인 표시(always_online)로 본다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SlackSocketModeRunner implements SmartLifecycle {

  static final String DISCONNECT_ALERT_KEY = "advisor:chat:disconnect-alert";

  private final AdvisorChatProperties properties;
  private final SlackChatRouter router;
  private final AdvisorNotifier notifier;
  private final RedissonClient redissonClient;

  private volatile SocketModeApp socketModeApp;
  private volatile boolean running;

  @Override
  public boolean isAutoStartup() {
    return properties.isRunnable();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * Bolt App 조립 → Socket Mode 연결. 모든 이벤트를 자동 ack 하고(핸들러 없는 subtype 이벤트가 404 로 재전송되지 않게) 두 이벤트만 라우터에 넘긴다.
   */
  @Override
  public void start() {
    if (running) {
      return;
    }
    if (!properties.isRunnable()) {
      log.warn("advisor chat Socket Mode 연결 생략 — 설정 누락 {}", properties.missing());
      return;
    }
    try {
      App app = new App(AppConfig.builder()
          .singleTeamBotToken(properties.botToken())
          .allEventsApiAutoAckEnabled(true)
          .build());
      app.event(MessageEvent.class, router::onMessage);
      app.event(MessageThreadBroadcastEvent.class, router::onThreadBroadcast);
      SocketModeApp socketMode = new SocketModeApp(properties.getAppToken(), SocketModeClient.Backend.JavaWebSocket, app);
      // run() 이 factory 로 client 를 만들고 connect 까지 하므로 리스너는 그 뒤에 붙인다(연결 실패는 여기서 예외로 드러난다)
      socketMode.startAsync();
      SocketModeClient client = socketMode.getClient();
      if (client != null) {
        client.addWebSocketCloseListener((code, reason) -> onDisconnect("close", code + " " + reason));
        client.addWebSocketErrorListener(t -> onDisconnect("error", String.valueOf(t)));
      }
      socketModeApp = socketMode;
      running = true;
      log.info("advisor chat Socket Mode 연결 시작: channel={}, allowedUsers={}명", properties.getChannelId(), properties.getAllowedUserIds().size());
    } catch (Exception e) {
      log.error("advisor chat Socket Mode 시작 실패(기동은 계속): {}", e.toString(), e);
      notifier.alert("[advisor chat] Slack Socket Mode 연결 시작 실패: " + e + "\nSLACK_APP_TOKEN(xapp-, connections:write)·Socket Mode 토글을 확인하세요", false);
    }
  }

  @Override
  public void stop() {
    SocketModeApp socketMode = socketModeApp;
    socketModeApp = null;
    running = false;
    if (socketMode == null) {
      return;
    }
    try {
      socketMode.close();
      log.info("advisor chat Socket Mode 연결 종료");
    } catch (Exception e) {
      log.warn("advisor chat Socket Mode 종료 중 예외(무시): {}", e.toString());
    }
  }

  /**
   * WebSocket close/error. SDK 가 재연결하므로 여기서는 기록과 경보만 — 경보는 쿨다운(Redis) 당 1회.
   */
  void onDisconnect(String kind, String detail) {
    log.warn("advisor chat WebSocket {}: {} — SDK 가 자동 재연결한다. 끊긴 동안 온 질문은 유실된다", kind, detail);
    if (allowAlert()) {
      notifier.alert(String.format("[advisor chat] Slack Socket Mode %s: %s%n자동 재연결 중 — 끊긴 동안 온 질문은 유실됩니다(다시 물어보면 됨)", kind, detail), false);
    }
  }

  private boolean allowAlert() {
    try {
      return redissonClient.getBucket(DISCONNECT_ALERT_KEY).setIfAbsent("1", Duration.ofMinutes(Math.max(1, properties.getDisconnectAlertCooldownMinutes())));
    } catch (Exception e) {
      return true;
    }
  }
}
