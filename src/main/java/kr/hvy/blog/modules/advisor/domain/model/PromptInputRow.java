package kr.hvy.blog.modules.advisor.domain.model;

import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;

/**
 * LLM 입력·출력 원문 스냅샷 (tb_advisor_prompt_input). payload·options·rawOutput 은 JSON 문자열.
 */
public record PromptInputRow(
    long runId,
    AdviceVariant variant,
    String promptVersion,
    String systemSha256,
    String userPayload,
    String optionsJson,
    String rawOutput) {
}
