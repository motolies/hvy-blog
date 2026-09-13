package kr.hvy.blog.modules.advisor.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import lombok.Builder;

/**
 * tb_advisor_chat 1행 — Slack 채팅 봇 질문·답변 감사.
 */
@Builder
public record ChatRow(long chatId, String eventId, String channelId, String threadTs, String messageTs, String slackUserId, String question,
                      String answer, AdvisorStatus status, String model, String promptVersion, int historyMessages, int toolCalls,
                      List<String> toolCallNames, int promptTokens, int completionTokens, int reasoningTokens, int cachedTokens,
                      BigDecimal costUsd, LocalDate dataAsOf, Long durationMs, String errorMessage, Instant createdAt, Instant updatedAt) {
}
