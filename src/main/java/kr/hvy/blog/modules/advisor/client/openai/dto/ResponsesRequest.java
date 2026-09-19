package kr.hvy.blog.modules.advisor.client.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;

/**
 * {@code POST /v1/responses} 요청 본문. null 필드는 {@code AdvisorJson}(NON_NULL) 이 생략하므로 선택 파라미터는 null 로 두면 보내지 않는다.
 * <p>
 * {@code input} 은 역할 메시지({@link InputMessage})·도구 결과({@link FunctionCallOutput})·이전 라운드 응답 원문(JsonNode)이 섞이므로 Object 목록이다.
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
    Boolean store,
    List<String> include) {

  /** reasoning 파라미터. effort 는 none/minimal/low/medium/high/xhigh/max 를 검증 없이 그대로 전달한다(OpenAI 가 거부하면 400 으로 드러난다) */
  public record Reasoning(String effort) {
  }
}
