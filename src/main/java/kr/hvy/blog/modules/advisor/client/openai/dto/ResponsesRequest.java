package kr.hvy.blog.modules.advisor.client.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/responses} 요청 본문. null 필드는 {@code AdvisorJson}(NON_NULL) 이 생략하므로 선택 파라미터는 null 로 두면 보내지 않는다.
 * <p>
 * {@code input} 은 역할 메시지({@link InputMessage})·도구 결과({@link FunctionCallOutput})·이전 라운드 응답 원문(JsonNode)이 섞이므로 Object 목록이다.
 * {@code text.format} 은 구조화 출력(judge/assist 의 strict JSON 스키마) — {@link #jsonSchema(ResponsesTextFormat)} 로 만든다.
 */
@Builder
public record ResponsesRequest(
    String model,
    String instructions,
    List<Object> input,
    List<FunctionTool> tools,
    @JsonProperty("tool_choice") String toolChoice,
    @JsonProperty("parallel_tool_calls") Boolean parallelToolCalls,
    Reasoning reasoning,
    @JsonProperty("max_output_tokens") Integer maxOutputTokens,
    Double temperature,
    Text text,
    Boolean store,
    List<String> include) {

  /** reasoning 파라미터. effort 는 none/minimal/low/medium/high/xhigh/max 를 검증 없이 그대로 전달한다(OpenAI 가 거부하면 400 으로 드러난다) */
  public record Reasoning(String effort) {
  }

  /** {@code text} 파라미터 — 지금은 format 만 쓴다 */
  public record Text(Format format) {
  }

  /** {@code text.format}. type 은 json_schema 고정, strict=true 면 서버가 스키마 준수를 보장한다(지원 부분집합: 모든 속성 required + additionalProperties=false) */
  public record Format(String type, String name, Boolean strict, JsonNode schema) {

    public static final String TYPE_JSON_SCHEMA = "json_schema";
  }

  /**
   * 구조화 출력 지정 → {@code text.format} json_schema.
   */
  public static Text jsonSchema(ResponsesTextFormat format) {
    return new Text(new Format(Format.TYPE_JSON_SCHEMA, format.name(), format.strict(), format.schema()));
  }
}
