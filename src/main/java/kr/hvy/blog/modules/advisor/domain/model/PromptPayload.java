package kr.hvy.blog.modules.advisor.domain.model;

import java.util.List;

/**
 * LLM 사용자 메시지(JSON 문자열)와 조립 메타. candidateTickers 는 스키마 enum 주입용(입력에 실제로 포함된 후보만).
 *
 * @param truncated 길이 상한 때문에 후보를 뒤에서 잘라냈는지
 */
public record PromptPayload(String json, List<String> candidateTickers, List<String> sectorCodes, int candidatesIncluded, boolean truncated,
                            int estimatedTokens) {
}
