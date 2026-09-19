package kr.hvy.blog.modules.advisor.application.chat;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Slack #hvy-advisor 채팅 봇 설정(yml {@code advisor.chat.*}, chat-v1 2026-09-13).
 * <p>
 * 손잡이를 전부 이 접두 아래 모은다 — {@code slack.*} 은 hvy-common {@code SlackProperty} 가 바인딩하는 접두라 같은 접두에 두 번째
 * {@code @ConfigurationProperties} 를 붙이지 않는다. bot 토큰만은 새 키를 만들지 않고 {@code slack.token}(env SLACK_BOT_TOKEN) 을
 * {@link Environment} 로 읽어 재사용한다({@link AdvisorProperties#openAiApiKey()} 와 같은 방식).
 * <p>
 * 검증은 기동을 막지 않는다 — 토큰·채널·허용 사용자 중 하나라도 비면 WARN 1줄을 남기고 {@link #isRunnable()} 이 false 가 되어 Socket Mode
 * 연결을 열지 않는다. 잘못된 Slack 설정으로 블로그 전체가 안 뜨는 손해가 채팅 봇 하나가 안 뜨는 손해보다 훨씬 크다.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "advisor.chat")
public class AdvisorChatProperties {

  static final String SLACK_BOT_TOKEN_PROPERTY = "slack.token";

  private final Environment environment;
  private final AdvisorProperties advisor;

  /** 채팅 봇 on/off. advisor.enabled 가 false 면 이 값과 무관하게 빈이 등록되지 않는다 */
  private boolean enabled = false;

  /** Socket Mode app-level 토큰(xapp-…, scope connections:write). env SLACK_APP_TOKEN */
  private String appToken;

  /** 질문을 받는 채널 ID(C…). 다른 채널 이벤트는 1단계 필터에서 버린다 */
  private String channelId;

  /** 답을 받을 Slack 사용자 ID(U…) 목록. 비어 있으면 아무에게도 답하지 않는다(= 연결하지 않는다) */
  private List<String> allowedUserIds = List.of();

  /** 채팅 모델 ID. 비면 advisor.model.assist */
  private String model;

  /** temperature. 추론 모델은 받지 않으므로 null 이면 설정하지 않는다 */
  private Double temperature;

  /**
   * Responses API 의 reasoning.effort (none/minimal/low/medium/high/xhigh/max). 비면 보내지 않는다 = OpenAI 서버 기본값.
   * 값은 검증 없이 그대로 전달한다 — 목록이 자주 늘어나고(xhigh·max), 틀리면 OpenAI 400 이 Slack 실패 한 줄에 그대로 보인다
   */
  private String reasoningEffort;

  /** 출력 상한 = Responses max_output_tokens. 추론 토큰이 같이 소모되므로 넉넉히 둔다 */
  private int maxCompletionTokens = 6_000;

  /** 도구별 호출 상한(ToolCallingManager.maxCallsPerTool) */
  private int maxCallsPerTool = 6;

  /** 질문 1건의 도구 호출 총합 상한(ToolCallingManager.maxTotalToolCalls) */
  private int maxTotalToolCalls = 12;

  /** conversations.replies 로 읽는 스레드 메시지 수 상한 */
  private int threadHistoryLimit = 30;

  /** 프롬프트에 넣는 스레드 히스토리 총 문자 상한 — 넘으면 오래된 것부터 버린다(루트는 보존) */
  private int maxHistoryChars = 12_000;

  /** 도구가 돌려주는 행 수 상한 */
  private int toolRowLimit = 50;

  /** 도구 SQL statement_timeout(초) */
  private int toolTimeoutSeconds = 5;

  /** 질문 1건 처리 소프트 마감(초). 넘으면 도구가 deadline 오류를 돌려주고 모델이 마무리한다 */
  private int answerTimeoutSeconds = 180;

  /** 일일 토큰 예산(입력+출력, KST 자정 기준). 0 이면 무제한 */
  private long dailyTokenBudget = 300_000;

  /** 같은 사용자의 연속 질문 최소 간격(초) */
  private int perUserCooldownSeconds = 20;

  /** 답글 Block Kit 블록 수 상한(메시지 상한 50 안) */
  private int maxBlocks = 20;

  /** WebSocket close/error 경보(#hvy-notify) 최소 간격(분) */
  private int disconnectAlertCooldownMinutes = 30;

  /** event_id 중복 제거 Redis TTL(분). DB uk_advisor_chat_event 가 2차 방어선 */
  private int dedupTtlMinutes = 10;

  /**
   * hvy-common SlackClient 와 같은 bot 토큰(slack.token). 없으면 빈 문자열.
   */
  public String botToken() {
    return StringUtils.defaultString(environment.getProperty(SLACK_BOT_TOKEN_PROPERTY));
  }

  /**
   * 정규화한 reasoning effort(trim·소문자). 비어 있으면 null = 요청에 넣지 않는다.
   */
  public String reasoningEffortOrNull() {
    String value = StringUtils.trimToNull(reasoningEffort);
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }

  /**
   * Socket Mode 연결을 열어도 되는가 — advisor·chat 둘 다 켜져 있고 필수 값이 전부 있어야 한다.
   */
  public boolean isRunnable() {
    return enabled && advisor.isEnabled() && missing().isEmpty();
  }

  /**
   * 비어 있는 필수 설정의 env 이름. 비어 있지 않으면 연결하지 않는다.
   */
  public List<String> missing() {
    List<String> missing = new ArrayList<>();
    if (StringUtils.isBlank(appToken)) {
      missing.add("SLACK_APP_TOKEN");
    }
    if (StringUtils.isBlank(botToken())) {
      missing.add("SLACK_BOT_TOKEN");
    }
    if (StringUtils.isBlank(channelId)) {
      missing.add("ADVISOR_CHAT_CHANNEL_ID");
    }
    if (allowedUserIds == null || allowedUserIds.isEmpty()) {
      missing.add("ADVISOR_CHAT_ALLOWED_USERS");
    }
    return missing;
  }

  /**
   * 허용 사용자 목록 정규화(trim·빈 항목 제거) 후 상태를 남긴다. 어떤 경우에도 예외를 던지지 않는다.
   */
  @PostConstruct
  void logStatus() {
    allowedUserIds = allowedUserIds == null ? List.of()
        : allowedUserIds.stream().map(s -> s == null ? "" : s.trim()).filter(s -> !s.isEmpty()).toList();
    for (String id : allowedUserIds) {
      if (!(id.startsWith("U") || id.startsWith("W"))) {
        log.warn("advisor.chat.allowed-user-ids 의 '{}' 는 Slack 사용자 ID(U…/W…) 형식이 아닙니다 — 이 항목은 아무 메시지와도 일치하지 않습니다", id);
      }
    }
    if (!enabled) {
      log.info("advisor chat 비활성(advisor.chat.enabled=false) — Socket Mode 연결 없음");
      return;
    }
    if (!advisor.isEnabled()) {
      log.warn("advisor.chat.enabled=true 이지만 advisor.enabled=false 라 채팅 봇을 켜지 않습니다(ChatClient 빈 없음)");
      return;
    }
    List<String> missing = missing();
    if (!missing.isEmpty()) {
      log.warn("advisor chat 설정 누락 {} — 기동은 계속하되 Socket Mode 연결을 열지 않습니다", missing);
      return;
    }
    log.info("advisor chat 활성: channel={}, allowedUsers={}명, model={}, reasoning={}, maxOutput={}, budget={}tok/일, tools≤{}회, history≤{}건/{}자",
        channelId, allowedUserIds.size(), StringUtils.defaultIfBlank(model, advisor.getModel().getAssist() + "(assist)"),
        StringUtils.defaultIfBlank(reasoningEffortOrNull(), "(서버 기본)"), maxCompletionTokens,
        dailyTokenBudget, maxTotalToolCalls, threadHistoryLimit, maxHistoryChars);
  }
}
