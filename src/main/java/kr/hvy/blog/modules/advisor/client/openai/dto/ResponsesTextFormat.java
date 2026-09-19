package kr.hvy.blog.modules.advisor.client.openai.dto;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Responses API 구조화 출력 지정({@code text.format} 의 json_schema). judge/assist 의 strict JSON 스키마(후보 티커 enum 주입)가 여기로 간다.
 * <p>
 * {@code name} 은 OpenAI 규칙 {@code ^[a-zA-Z0-9_-]{1,64}$} 을 생성 시점에 검증한다 — 서버 400 보다 먼저, 호출 전에 드러나게.
 */
public record ResponsesTextFormat(String name, JsonNode schema, boolean strict) {

  private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

  public ResponsesTextFormat {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("text.format name 은 ^[a-zA-Z0-9_-]{1,64}$ 이어야 합니다: " + name);
    }
    if (schema == null || !schema.isObject()) {
      throw new IllegalArgumentException("text.format schema 는 JSON 객체여야 합니다: " + name);
    }
  }

  /**
   * strict 스키마 포맷. 모든 속성 required + additionalProperties=false 는 호출측 스키마 팩토리가 보장한다.
   */
  public static ResponsesTextFormat strictJsonSchema(String name, JsonNode schema) {
    return new ResponsesTextFormat(name, schema, true);
  }
}
