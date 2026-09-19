package kr.hvy.blog.modules.advisor.client.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/responses} 응답. 비-2xx 본문({@code {"error": {...}}})도 같은 레코드로 읽는다(다른 필드는 null).
 * <p>
 * {@code output} 항목은 원문 JsonNode 로 둔다 — reasoning(encrypted_content)·function_call·message 를 다음 라운드 input 에 그대로 되돌려 보내야 하고,
 * 미지 필드를 잃으면 안 되기 때문이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponsesResponse(
    String id,
    String status,
    String model,
    List<JsonNode> output,
    Usage usage,
    @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
    ApiError error) {

  public static final String STATUS_COMPLETED = "completed";
  public static final String STATUS_INCOMPLETE = "incomplete";
  public static final String STATUS_FAILED = "failed";

  public static final String ITEM_MESSAGE = "message";
  public static final String ITEM_FUNCTION_CALL = "function_call";
  public static final String CONTENT_OUTPUT_TEXT = "output_text";
  /** 구조화 출력에서 모델이 스키마 대신 거부를 택한 content 파트 — {@code refusal} 필드에 문구 */
  public static final String CONTENT_REFUSAL = "refusal";
  /** incomplete_details.reason 중 출력 상한 도달 */
  public static final String INCOMPLETE_MAX_OUTPUT_TOKENS = "max_output_tokens";

  public ResponsesResponse {
    output = output == null ? List.of() : List.copyOf(output);
  }

  public boolean isFailed() {
    return STATUS_FAILED.equals(status);
  }

  public boolean isIncomplete() {
    return STATUS_INCOMPLETE.equals(status);
  }

  /**
   * status=incomplete 의 사유(max_output_tokens·content_filter …). 미완이 아니면 null.
   */
  public String incompleteReason() {
    return incompleteDetails == null ? null : incompleteDetails.reason();
  }

  /**
   * 실패·미완 사유 한 줄 (없으면 null).
   */
  public String reason() {
    if (error != null && error.message() != null) {
      return error.message();
    }
    return incompleteDetails == null ? null : incompleteDetails.reason();
  }

  /** 토큰 사용량. 추론 토큰은 output_tokens 안에 포함돼 있고 상세에만 따로 보인다 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Usage(
      @JsonProperty("input_tokens") Integer inputTokens,
      @JsonProperty("output_tokens") Integer outputTokens,
      @JsonProperty("total_tokens") Integer totalTokens,
      @JsonProperty("input_tokens_details") InputTokensDetails inputTokensDetails,
      @JsonProperty("output_tokens_details") OutputTokensDetails outputTokensDetails) {

    public int reasoningTokens() {
      return outputTokensDetails == null || outputTokensDetails.reasoningTokens() == null ? 0 : outputTokensDetails.reasoningTokens();
    }

    public int cachedTokens() {
      return inputTokensDetails == null || inputTokensDetails.cachedTokens() == null ? 0 : inputTokensDetails.cachedTokens();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InputTokensDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OutputTokensDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
  }

  /** status=incomplete 의 사유 (예: max_output_tokens) */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IncompleteDetails(String reason) {
  }

  /** OpenAI 오류 객체 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ApiError(String type, String code, String message) {
  }
}
