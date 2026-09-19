package kr.hvy.blog.modules.advisor.client.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 도구 실행 결과 입력 항목. {@code call_id} 는 모델이 준 function_call 의 call_id 와 같아야 한다.
 */
public record FunctionCallOutput(String type, @JsonProperty("call_id") String callId, String output) {

  public static final String TYPE = "function_call_output";

  /**
   * Spring AI {@code ToolResponse(id, name, responseData)} 를 입력 항목으로 만든다. 결과가 null 이면 빈 문자열.
   */
  public static FunctionCallOutput of(String callId, String output) {
    return new FunctionCallOutput(TYPE, callId, output == null ? "" : output);
  }
}
