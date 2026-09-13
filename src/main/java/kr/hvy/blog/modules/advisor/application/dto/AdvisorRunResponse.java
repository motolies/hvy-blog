package kr.hvy.blog.modules.advisor.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;

/**
 * advisor run 응답.
 */
public record AdvisorRunResponse(
    Long runId,
    AdvisorJobType jobType,
    String jobDescription,
    AdvisorTriggerType triggerType,
    AdvisorStatus status,
    LocalDate baseDate,
    String model,
    String promptVersion,
    int llmCalls,
    int promptTokens,
    int completionTokens,
    int reasoningTokens,
    int cachedTokens,
    BigDecimal costUsd,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String errorMessage,
    Map<String, Object> metadata) {

  public static AdvisorRunResponse from(AdvisorRun run) {
    return new AdvisorRunResponse(run.getRunId(), run.getJobType(), run.getJobType().getDesc(), run.getTriggerType(), run.getStatus(),
        run.getBaseDate(), run.getModel(), run.getPromptVersion(), run.getLlmCalls(), run.getPromptTokens(), run.getCompletionTokens(),
        run.getReasoningTokens(), run.getCachedTokens(), run.getCostUsd(), run.getStartedAt(), run.getFinishedAt(), run.getDurationMs(),
        run.getErrorMessage(), run.getMetadataJson());
  }
}
