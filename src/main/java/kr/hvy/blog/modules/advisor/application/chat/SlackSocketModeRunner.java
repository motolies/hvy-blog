package kr.hvy.blog.modules.advisor.application.chat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageThreadBroadcastEvent;
import com.slack.api.socket_mode.SocketModeClient;
import com.slack.api.util.json.GsonFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 비정상 close/error 는 리스너가 WARN 과 #hvy-notify 경보(쿨다운 당 1회)를 남긴다(refresh 의 정상 종료 1000 은 INFO). 평시 연결 상태는 Slack 사이드바의 봇 온라인 표시(always_online)로 본다.
 * <p>
 * Socket Mode 연결은 <b>Slack 앱 단위</b>로 묶이고 Slack 은 이벤트를 열린 연결 중 하나에만 보낸다. 같은 App-Level Token 으로 다른 프로세스가 붙어 있으면
 * 질문이 그쪽으로 새어 로그 한 줄 없이 무응답이 되므로, hello 의 num_connections 를 기록하고 기동 직후 2 이상이면 경보한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SlackSocketModeRunner implements SmartLifecycle {

  static final String DISCONNECT_ALERT_KEY = "advisor:chat:disconnect-alert";
  /** WebSocket 정상 종료 코드 — SDK 가 연결 refresh(약 5시간 주기) 때 옛 세션을 이 코드로 닫는다 */
  static final int NORMAL_CLOSE = 1000;

  private static final Gson GSON = GsonFactory.createSnakeCase();

  private final AdvisorChatProperties properties;
  private final SlackChatRouter router;
  private final AdvisorNotifier notifier;
  private final RedissonClient redissonClient;

  private final AtomicBoolean firstHelloSeen = new AtomicBoolean();
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
      firstHelloSeen.set(false);
      socketMode.startAsync();
      SocketModeClient client = socketMode.getClient();
      if (client != null) {
        client.addWebSocketMessageListener(this::onRawMessage);
        client.addWebSocketCloseListener(this::onClose);
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
   * 수신 원문 중 hello 만 골라 앱 단위 연결 수를 남긴다. 2 이상이면 다른 연결이 이벤트를 나눠 받아 질문이 유실된다.
   * 경보는 기동 후 첫 hello 에서만 — 새 JVM 은 세션이 1개라 오탐이 없고, refresh 시점의 hello 는 옛 세션이 잠깐 겹쳐 보일 수 있다.
   */
  void onRawMessage(String message) {
    Integer connections = parseHelloConnections(message);
    if (connections == null) {
      return;
    }
    boolean first = firstHelloSeen.compareAndSet(false, true);
    if (connections <= 1) {
      log.info("advisor chat Socket Mode hello: connections={}", connections);
      return;
    }
    log.warn("advisor chat Socket Mode hello: connections={} — 같은 Slack 앱의 다른 Socket Mode 연결이 이벤트를 나눠 받는다(App-Level Token 재발급 필요)", connections);
    if (first) {
      notifier.alert(String.format("[advisor chat] Slack Socket Mode 연결이 %d개입니다 — 이벤트는 연결 중 하나에만 전달되므로 질문이 다른 연결로 새어 무응답이 됩니다%n"
          + "App-Level Token 을 Revoke→재발급해 blogback 에만 넣으세요", connections), false);
    }
  }

  /**
   * hello 원문에서 num_connections 를 뽑는다. hello 가 아니거나 깨진 JSON 이면 null — 모든 이벤트를 두 번 파싱하지 않도록 문자열로 먼저 거른다.
   */
  static Integer parseHelloConnections(String message) {
    if (message == null || !message.contains("\"hello\"")) {
      return null;
    }
    try {
      JsonObject json = GSON.fromJson(message, JsonObject.class);
      JsonElement type = json == null ? null : json.get("type");
      if (type == null || !type.isJsonPrimitive() || !"hello".equals(type.getAsString())) {
        return null;
      }
      JsonElement connections = json.get("num_connections");
      return connections == null || connections.isJsonNull() ? null : connections.getAsInt();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * WebSocket close. 정상 종료(1000)는 SDK 가 refresh 때 새 세션을 연 뒤 옛 세션을 닫는 것이고 종료 중의 close 도 의도된 것이라 INFO 만 남긴다.
   */
  void onClose(Integer code, String reason) {
    if (!isAbnormalClose(code, running)) {
      log.info("advisor chat WebSocket close: {} {} — 정상 종료(SDK 연결 refresh 또는 종료 중)", code, reason);
      return;
    }
    onDisconnect("close", code + " " + reason);
  }

  /**
   * 경보할 만한 close 인지 — 실행 중인데 정상 종료 코드가 아닐 때만.
   */
  static boolean isAbnormalClose(Integer code, boolean running) {
    return running && (code == null || code != NORMAL_CLOSE);
  }

  /**
   * 비정상 close/error. SDK 가 재연결하므로 여기서는 기록과 경보만 — 경보는 쿨다운(Redis) 당 1회.
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
