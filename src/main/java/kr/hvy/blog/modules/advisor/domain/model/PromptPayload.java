package kr.hvy.blog.modules.advisor.domain.model;

import java.util.List;

/**
 * LLM 사용자 메시지(JSON 문자열)와 조립 메타. candidateTickers 는 스키마 enum 주입용(입력에 실제로 포함된 후보만), newsIds 는 citedNews enum 주입용
 * (자 상한으로 줄인 뒤 실제로 실린 헤드라인 id 만, 뉴스 없으면 빈 목록).
 *
 * @param truncated 길이 상한 때문에 후보를 뒤에서 잘라냈는지
 */
public record PromptPayload(String json, List<String> candidateTickers, List<String> sectorCodes, int candidatesIncluded, boolean truncated,
                            int estimatedTokens, List<String> newsIds) {

  public PromptPayload(String json, List<String> candidateTickers, List<String> sectorCodes, int candidatesIncluded, boolean truncated,
      int estimatedTokens) {
    this(json, candidateTickers, sectorCodes, candidatesIncluded, truncated, estimatedTokens, List.of());
  }
}
