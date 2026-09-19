package kr.hvy.blog.modules.advisor.client.openai.dto;

import tools.jackson.databind.JsonNode;

/**
 * Responses API 의 function 도구 정의. {@code parameters} 는 Spring AI {@code ToolDefinition.inputSchema()} 원문에서 루트 {@code $schema} 만 뺀 것.
 * <p>
 * {@code strict} 는 false — Spring AI 생성 스키마는 optional 파라미터를 {@code required} 에 넣지 않아 strict 규칙(모든 속성 required + nullable 타입)과 맞지 않는다.
 */
public record FunctionTool(String type, String name, String description, JsonNode parameters, Boolean strict) {

  public static final String TYPE_FUNCTION = "function";

  /**
   * strict 가 아닌 function 도구를 만든다.
   */
  public static FunctionTool function(String name, String description, JsonNode parameters) {
    return new FunctionTool(TYPE_FUNCTION, name, description, parameters, Boolean.FALSE);
  }
}
