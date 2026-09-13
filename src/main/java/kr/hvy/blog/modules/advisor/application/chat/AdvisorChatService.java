package kr.hvy.blog.modules.advisor.application.chat;

import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;

import com.slack.api.model.block.LayoutBlock;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.repository.jdbc.ChatWriter;
import kr.hvy.common.observability.TraceBoundary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 질문 1건의 전체 수명 — 감사 행 RUNNING → 👀 리액션 → 예산·쿨다운({@link ChatBudgetGuard}, 거부면 SKIPPED + 한 줄 + ⚠) → 답 생성({@link ChatAnswerer}, 없으면 에코) → 스레드 답글 → 감사 SUCCESS → ✅.
 * <p>
 * 어떤 예외도 밖으로 던지지 않는다. 전역 예외 핸들러가 500 + #hvy-error 를 울리는 구조라, 실패는 (a) 스레드에 한 줄 답글, (b) FAILED + 사유,
 * (c) ⚠ 리액션까지만이다. 질문 단위 실패는 별도 Slack 경보를 보내지 않는다 — 질문자가 스레드에서 이미 본다.
 * 실행기 스레드에서 돌므로 {@link TraceBoundary} 로 감싸 이 질문의 로그가 하나의 traceId 를 갖게 한다(테스트 프로파일에는 빈이 없어 생략).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorChatService {

  static final String REACTION_WORKING = "eyes";
  static final String REACTION_DONE = "white_check_mark";
  static final String REACTION_FAILED = "warning";
  static final String ECHO_MODEL = "echo";
  static final String ECHO_VERSION = "echo-v0";
  static final String BUSY_TEXT = "지금 처리 중인 질문이 많습니다. 잠시 후 다시 물어봐 주세요.";

  private final AdvisorChatProperties properties;
  private final ChatWriter chatWriter;
  private final SlackChatGateway slack;
  private final ChatBudgetGuard budget;
  private final ObjectProvider<ChatAnswerer> answerer;
  private final ObjectProvider<TraceBoundary> traceBoundary;

  public AdvisorChatService(AdvisorChatProperties properties, ChatWriter chatWriter, SlackChatGateway slack, ChatBudgetGuard budget,
      ObjectProvider<ChatAnswerer> answerer, ObjectProvider<TraceBoundary> traceBoundary) {
    this.properties = properties;
    this.chatWriter = chatWriter;
    this.slack = slack;
    this.budget = budget;
    this.answerer = answerer;
    this.traceBoundary = traceBoundary;
  }

  /**
   * 실행기 진입점. trace 경계를 열고 처리한다.
   */
  public void handle(IncomingQuestion question) {
    TraceBoundary boundary = traceBoundary.getIfAvailable();
    if (boundary == null) {
      process(question);
    } else {
      boundary.run("advisor.chat", () -> process(question));
    }
  }

  /**
   * 실제 처리. 감사 INSERT 가 비어 오면(DB 유니크 충돌 = 재전송) 아무것도 하지 않는다.
   */
  void process(IncomingQuestion q) {
    long started = System.currentTimeMillis();
    Optional<Long> inserted;
    try {
      inserted = chatWriter.insertRunning(q);
    } catch (Exception e) {
      log.error("advisor chat 감사 행 생성 실패 — 질문을 처리하지 않는다: eventId={}, cause={}", q.eventId(), e.toString(), e);
      return;
    }
    if (inserted.isEmpty()) {
      log.info("이미 처리한 Slack 이벤트(DB 유니크) — 무시: eventId={}", q.eventId());
      return;
    }
    long chatId = inserted.get();
    slack.addReaction(q.channelId(), q.ts(), REACTION_WORKING);
    Optional<ChatBudgetGuard.Refusal> refusal = budget.check(q);
    if (refusal.isPresent()) {
      log.info("advisor chat 거부({}): chat={}, user={}", refusal.get().code(), chatId, q.userId());
      try {
        chatWriter.finishSkipped(chatId, refusal.get().code() + ": " + refusal.get().message(), elapsed(started));
      } catch (Exception e) {
        log.error("advisor chat SKIPPED 기록 실패: chat={}, cause={}", chatId, e.toString());
      }
      safeReply(q, refusal.get().message());
      swapReaction(q, REACTION_FAILED);
      return;
    }
    try {
      ChatResult result = answer(q);
      List<LayoutBlock> blocks = SlackMarkdown.replyBlocks(result.answer(), contextLine(chatId, result), properties.getMaxBlocks());
      slack.postReply(q.channelId(), q.replyThreadTs(), SlackMarkdown.fallback(result.answer()), blocks);
      chatWriter.finishSuccess(chatId, result, elapsed(started));
      swapReaction(q, REACTION_DONE);
      log.info("advisor chat 답변: chat={}, user={}, model={}, tools={}, in={}, out={}, {}ms", chatId, q.userId(), result.model(), result.toolCalls(),
          result.promptTokens(), result.completionTokens(), elapsed(started));
    } catch (Exception e) {
      log.error("advisor chat 실패: chat={}, user={}, cause={}", chatId, q.userId(), e.toString(), e);
      try {
        chatWriter.finishFailed(chatId, abbreviate(e.toString(), 1_000), elapsed(started));
      } catch (Exception writeFailure) {
        log.error("advisor chat FAILED 기록 실패: chat={}, cause={}", chatId, writeFailure.toString());
      }
      safeReply(q, "답변을 만들지 못했습니다: " + abbreviate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 200));
      swapReaction(q, REACTION_FAILED);
    }
  }

  /**
   * 실행기 큐 포화 시 라우터가 부른다(동기, 1회). 실패는 삼킨다.
   */
  public void replyBusy(IncomingQuestion q) {
    safeReply(q, BUSY_TEXT);
  }

  /**
   * 답 생성. {@link ChatAnswerer} 빈이 없으면(P1) 질문을 그대로 되돌려 수신·감사·생명주기를 검증한다.
   */
  ChatResult answer(IncomingQuestion q) {
    ChatAnswerer bean = answerer.getIfAvailable();
    if (bean != null) {
      return bean.answer(q);
    }
    return ChatResult.builder().answer("질문을 받았습니다: " + q.text()).model(ECHO_MODEL).promptVersion(ECHO_VERSION).build();
  }

  /**
   * 답글 꼬리의 메타 줄 — DailyAdviceMessage 의 run 메타 줄과 같은 모양. 도구 목록을 드러내 "이 답이 어떤 데이터를 봤는가" 를 바로 알 수 있게 한다.
   */
  static String contextLine(long chatId, ChatResult r) {
    String tools = r.toolCalls().isEmpty() ? "없음" : String.join(", ", r.toolCalls());
    String asOf = r.dataAsOf() == null ? "" : " · 기준 " + r.dataAsOf();
    return String.format("chat=%d · model=%s · tools: %s · in %,d / out %,d tok%s", chatId, r.model(), tools, r.promptTokens(), r.completionTokens(), asOf);
  }

  private void safeReply(IncomingQuestion q, String text) {
    try {
      slack.postReply(q.channelId(), q.replyThreadTs(), text, List.of(section(s -> s.text(markdownText(text)))));
    } catch (Exception e) {
      log.warn("advisor chat 안내 답글 실패(무시): {}", e.toString());
    }
  }

  private void swapReaction(IncomingQuestion q, String name) {
    slack.removeReaction(q.channelId(), q.ts(), REACTION_WORKING);
    slack.addReaction(q.channelId(), q.ts(), name);
  }

  private static long elapsed(long started) {
    return System.currentTimeMillis() - started;
  }

  private static String abbreviate(String s, int max) {
    return s == null ? null : s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
