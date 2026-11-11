package kr.hvy.blog.modules.advisor.client.llm;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 공급자 중립 구조화 출력 지정 — strict JSON 스키마 하나와 그 이름. judge/assist 의 스키마(후보 티커 enum 주입)가 여기로 온다.
 * 공급자별 모델이 자기 요청 형식으로 옮긴다: OpenAI Responses 는 {@code text.format}(json_schema, strict), Claude Messages 는 {@code output_config.format}.
 * <p>
 * {@code name} 은 두 공급자가 모두 받는 {@code ^[a-zA-Z0-9_-]{1,64}$} 를 생성 시점에 검증한다 — 서버 400 보다 먼저, 호출 전에 드러나게.
 * 스키마는 모든 객체가 {@code additionalProperties=false}·전 속성 required 여야 하고(호출측 스키마 팩토리가 보장), 두 공급자의 공통 부분집합만 쓴다
 * (minimum·maxLength·minItems>1 같은 제약은 Claude 가 400 으로 거부한다).
 */
public record LlmJsonSchema(String name, JsonNode schema) {

  private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

  public LlmJsonSchema {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("JSON 스키마 이름은 ^[a-zA-Z0-9_-]{1,64}$ 이어야 합니다: " + name);
    }
    if (schema == null || !schema.isObject()) {
      throw new IllegalArgumentException("JSON 스키마는 JSON 객체여야 합니다: " + name);
    }
  }
}
