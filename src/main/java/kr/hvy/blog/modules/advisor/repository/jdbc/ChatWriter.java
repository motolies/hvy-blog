package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.chat.ChatResult;
import kr.hvy.blog.modules.advisor.application.chat.IncomingQuestion;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.model.ChatRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slack 채팅 봇 감사(tb_advisor_chat) 저장·조회. 질문 수신 시 RUNNING 으로 넣고 답글 뒤 SUCCESS/FAILED/SKIPPED 로 닫는다.
 * <p>
 * INSERT 는 {@code ON CONFLICT (event_id) DO NOTHING} — Redis 중복 제거가 비어 있을 때(재시작) 재전송을 조용히 흡수하는 2차 방어선이다.
 */
@Repository
@RequiredArgsConstructor
public class ChatWriter {

  private static final String COLUMNS = "chat_id, event_id, channel_id, thread_ts, message_ts, slack_user_id, question, answer, status, model, prompt_version, "
      + "history_messages, tool_calls, tool_calls_json, prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens, cost_usd, data_as_of, "
      + "duration_ms, error_message, created_at, updated_at";

  private final JdbcTemplate jdbc;

  /**
   * RUNNING 행을 만든다. 같은 event_id 가 이미 있으면(재전송) 빈 Optional — 호출자는 아무것도 하지 않는다.
   */
  @Transactional
  public Optional<Long> insertRunning(IncomingQuestion q) {
    List<Long> ids = jdbc.query("INSERT INTO tb_advisor_chat (event_id, channel_id, thread_ts, message_ts, slack_user_id, question, status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING RETURNING chat_id",
        (rs, i) -> rs.getLong(1), q.eventId(), q.channelId(), q.replyThreadTs(), q.ts(), q.userId(), q.text(), AdvisorStatus.RUNNING.getCode());
    return ids.stream().findFirst();
  }

  @Transactional
  public void finishSuccess(long chatId, ChatResult r, long durationMs) {
    jdbc.update("UPDATE tb_advisor_chat SET status = ?, answer = ?, model = ?, prompt_version = ?, history_messages = ?, tool_calls = ?, tool_calls_json = ?, "
            + "prompt_tokens = ?, completion_tokens = ?, reasoning_tokens = ?, cached_tokens = ?, cost_usd = ?, data_as_of = ?, duration_ms = ?, "
            + "error_message = NULL, updated_at = NOW() WHERE chat_id = ?",
        AdvisorStatus.SUCCESS.getCode(), r.answer(), r.model(), r.promptVersion(), r.historyMessages(), r.toolCalls().size(), AdvisorJdbc.jsonb(r.toolCalls()),
        r.promptTokens(), r.completionTokens(), r.reasoningTokens(), r.cachedTokens(), r.costUsd(), r.dataAsOf(), durationMs, chatId);
  }

  /**
   * 예산·쿨다운 거부 등 LLM 을 부르지 않고 닫은 경우.
   */
  @Transactional
  public void finishSkipped(long chatId, String reason, long durationMs) {
    jdbc.update("UPDATE tb_advisor_chat SET status = ?, error_message = ?, duration_ms = ?, updated_at = NOW() WHERE chat_id = ?",
        AdvisorStatus.SKIPPED.getCode(), reason, durationMs, chatId);
  }

  @Transactional
  public void finishFailed(long chatId, String error, long durationMs) {
    jdbc.update("UPDATE tb_advisor_chat SET status = ?, error_message = ?, duration_ms = ?, updated_at = NOW() WHERE chat_id = ?",
        AdvisorStatus.FAILED.getCode(), error, durationMs, chatId);
  }

  /**
   * 특정 시각 이후 생성된 행의 입력+출력 토큰 합계(일일 예산 판정용).
   */
  public long tokensSince(Instant since) {
    Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM tb_advisor_chat WHERE created_at >= ?", Long.class,
        AdvisorJdbc.ts(since));
    return sum == null ? 0L : sum;
  }

  public Optional<ChatRow> findById(long chatId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_chat WHERE chat_id = ?", MAPPER, chatId).stream().findFirst();
  }

  public List<ChatRow> findRecent(int limit) {
    return jdbc.query("SELECT " + COLUMNS + " FROM tb_advisor_chat ORDER BY created_at DESC, chat_id DESC LIMIT ?", MAPPER, limit);
  }

  static final RowMapper<ChatRow> MAPPER = (rs, i) -> ChatRow.builder()
      .chatId(rs.getLong("chat_id"))
      .eventId(rs.getString("event_id"))
      .channelId(rs.getString("channel_id"))
      .threadTs(rs.getString("thread_ts"))
      .messageTs(rs.getString("message_ts"))
      .slackUserId(rs.getString("slack_user_id"))
      .question(rs.getString("question"))
      .answer(rs.getString("answer"))
      .status(AdvisorJdbc.enumOrNull(rs, "status", AdvisorStatus.class))
      .model(rs.getString("model"))
      .promptVersion(rs.getString("prompt_version"))
      .historyMessages(rs.getInt("history_messages"))
      .toolCalls(rs.getInt("tool_calls"))
      .toolCallNames(AdvisorJdbc.jsonListOfString(rs, "tool_calls_json"))
      .promptTokens(rs.getInt("prompt_tokens"))
      .completionTokens(rs.getInt("completion_tokens"))
      .reasoningTokens(rs.getInt("reasoning_tokens"))
      .cachedTokens(rs.getInt("cached_tokens"))
      .costUsd(rs.getBigDecimal("cost_usd"))
      .dataAsOf(rs.getObject("data_as_of", LocalDate.class))
      .durationMs(AdvisorJdbc.nullableLong(rs, "duration_ms"))
      .errorMessage(rs.getString("error_message"))
      .createdAt(AdvisorJdbc.instant(rs, "created_at"))
      .updatedAt(AdvisorJdbc.instant(rs, "updated_at"))
      .build();
}
