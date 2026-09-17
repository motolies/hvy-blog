package kr.hvy.blog.modules.advisor.application.chat;

import com.slack.api.app_backend.events.payload.EventsApiPayload;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.response.Response;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageThreadBroadcastEvent;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Slack 메시지 이벤트의 관문 — 필터(채널·봇·허용 사용자·본문·중복) 뒤 통과한 질문만 실행기에 넘기고 <b>항상 즉시 ack</b> 한다.
 * <p>
 * Socket Mode 는 ack 를 3초 안에 받지 못하면 같은 envelope 를 재전송하므로, 거부된 메시지도 ack 하고 여기서는 DB·LLM·Slack API 를 부르지 않는다
 * (Redis 중복 판정 1회는 로컬 ms 라 허용). 어떤 예외도 밖으로 내지 않는다 — 예외가 새면 Bolt 가 ack 를 건너뛰어 재전송 폭주가 된다.
 * <p>
 * subtype 이 있는 메시지(수정·삭제·bot_message·file_share…)는 Bolt 가 별도 이벤트 클래스로 분기하고 이 라우터는 {@link MessageEvent}(subtype 없음)와
 * {@link MessageThreadBroadcastEvent}("채널에도 보내기" 답글)만 등록하므로 나머지는 자동 ack 로 버려진다(AppConfig.allEventsApiAutoAckEnabled).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class SlackChatRouter {

  /** 필터 판정 — 테스트에서 진리표로 검증한다. advisor 규약대로 EnumCode(code == 상수명) */
  @Getter
  @AllArgsConstructor
  public enum Verdict implements EnumCode<String> {
    ACCEPT("ACCEPT", "처리 대상"),
    OTHER_CHANNEL("OTHER_CHANNEL", "대상 채널 아님"),
    BOT_MESSAGE("BOT_MESSAGE", "봇이 보낸 메시지"),
    NO_USER("NO_USER", "보낸 사용자 없음"),
    SELF_MESSAGE("SELF_MESSAGE", "봇 자신의 메시지"),
    USER_NOT_ALLOWED("USER_NOT_ALLOWED", "허용 목록 밖 사용자"),
    EMPTY_TEXT("EMPTY_TEXT", "본문 없음"),
    DUPLICATE("DUPLICATE", "중복 이벤트"),
    REJECTED_BUSY("REJECTED_BUSY", "실행기 큐 포화"),
    ERROR("ERROR", "라우팅 중 예외");

    private final String code;
    private final String desc;
  }

  private final AdvisorChatProperties properties;
  private final ChatEventDeduplicator deduplicator;
  private final AdvisorChatService service;
  private final Executor executor;

  public SlackChatRouter(AdvisorChatProperties properties, ChatEventDeduplicator deduplicator, AdvisorChatService service,
      @Qualifier("advisorChatExecutor") Executor executor) {
    this.properties = properties;
    this.deduplicator = deduplicator;
    this.service = service;
    this.executor = executor;
  }

  /**
   * 일반 메시지(새 글·스레드 댓글). Bolt 이벤트 핸들러 시그니처.
   */
  public Response onMessage(EventsApiPayload<MessageEvent> payload, EventContext ctx) {
    MessageEvent e = payload.getEvent();
    route(new IncomingQuestion(payload.getEventId(), e.getChannel(), e.getUser(), e.getBotId(), e.getTs(), e.getThreadTs(), e.getText()), ctx.getBotUserId());
    return ctx.ack();
  }

  /**
   * 스레드 답글을 "채널에도 보내기" 로 올린 메시지(subtype thread_broadcast). bot_id 필드가 없어 봇 판정은 user id 로만 한다.
   */
  public Response onThreadBroadcast(EventsApiPayload<MessageThreadBroadcastEvent> payload, EventContext ctx) {
    MessageThreadBroadcastEvent e = payload.getEvent();
    route(new IncomingQuestion(payload.getEventId(), e.getChannel(), e.getUser(), null, e.getTs(), e.getThreadTs(), e.getText()), ctx.getBotUserId());
    return ctx.ack();
  }

  /**
   * 필터 → 중복 제거 → 실행기 제출. 실행기 큐가 차면 스레드에 한 줄 안내(동기 1회 호출)하고 버린다.
   */
  Verdict route(IncomingQuestion q, String botUserId) {
    try {
      Verdict verdict = decide(q, properties.getChannelId(), properties.getAllowedUserIds(), botUserId);
      if (verdict == Verdict.OTHER_CHANNEL) {
        // 봇이 알림 채널 여러 곳에 있어 다른 채널 메시지는 상시 들어온다 — 소음이라 DEBUG
        log.debug("Slack 메시지 무시({}): channel={}, user={}, ts={}", verdict, q.channelId(), q.userId(), q.ts());
        return verdict;
      }
      if (verdict != Verdict.ACCEPT) {
        // 대상 채널 안에서 버려지는 것은 "왜 답이 없나" 의 직접 원인이라 운영 레벨에서 보이게 둔다
        log.info("Slack 메시지 무시({}): channel={}, user={}, ts={}", verdict, q.channelId(), q.userId(), q.ts());
        return verdict;
      }
      if (!deduplicator.firstSeen(q.eventId())) {
        log.info("Slack 이벤트 중복 무시: eventId={}", q.eventId());
        return Verdict.DUPLICATE;
      }
      try {
        executor.execute(() -> service.handle(q));
        return Verdict.ACCEPT;
      } catch (RejectedExecutionException e) {
        log.warn("advisor chat 실행기 큐 포화 — 질문을 버린다: user={}, ts={}", q.userId(), q.ts());
        service.replyBusy(q);
        return Verdict.REJECTED_BUSY;
      }
    } catch (Exception e) {
      log.error("advisor chat 라우팅 실패(ack 는 진행): {}", e.toString(), e);
      return Verdict.ERROR;
    }
  }

  /**
   * 순수 판정. 싼 것부터 — 채널 → 봇 → 사용자 존재 → 자기 자신 → 허용 목록 → 본문.
   */
  static Verdict decide(IncomingQuestion q, String channelId, Collection<String> allowedUserIds, String botUserId) {
    if (channelId == null || channelId.isBlank() || !channelId.equals(q.channelId())) {
      return Verdict.OTHER_CHANNEL;
    }
    if (q.botId() != null && !q.botId().isBlank()) {
      return Verdict.BOT_MESSAGE;
    }
    if (q.userId() == null || q.userId().isBlank()) {
      return Verdict.NO_USER;
    }
    if (q.userId().equals(botUserId)) {
      return Verdict.SELF_MESSAGE;
    }
    if (allowedUserIds == null || !allowedUserIds.contains(q.userId())) {
      return Verdict.USER_NOT_ALLOWED;
    }
    if (q.text() == null || q.text().isBlank()) {
      return Verdict.EMPTY_TEXT;
    }
    return Verdict.ACCEPT;
  }
}
