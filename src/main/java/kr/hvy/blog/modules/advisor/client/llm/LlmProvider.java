package kr.hvy.blog.modules.advisor.client.llm;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * advisor 가 쓰는 LLM 공급자. Claude 가 1순위이고 쓸 수 없을 때 OpenAI 로 폴백한다({@link FallbackChatModel}, 2026-11-11).
 * <p>
 * 응답 메타데이터({@link LlmResponses#PROVIDER_METADATA_KEY})에는 상수명 문자열로 싣는다 — 메타데이터 맵이 복사·직렬화돼도 값이 살도록.
 * code 는 상수명과 같다(advisor enum 규약, AdvisorEnumCodeTest).
 */
@Getter
@AllArgsConstructor
public enum LlmProvider implements EnumCode<String> {
  CLAUDE("CLAUDE", "Anthropic Claude(Messages API) — 1순위"),
  OPENAI("OPENAI", "OpenAI(Responses API) — 폴백");

  private final String code;
  private final String desc;

  /**
   * 메타데이터 값(상수명 문자열 또는 enum)에서 공급자를 읽는다. null·모르는 값이면 null — 메타데이터가 없는 메시지(Slack 히스토리·테스트 스텁)를 뜻한다.
   */
  public static LlmProvider from(Object value) {
    if (value instanceof LlmProvider provider) {
      return provider;
    }
    if (value instanceof String name) {
      for (LlmProvider provider : values()) {
        if (provider.name().equals(name)) {
          return provider;
        }
      }
    }
    return null;
  }
}
