package kr.hvy.blog.modules.advisor.client.openai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesTextFormat;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * {@link OpenAiResponsesChatModel} 의 옵션. Spring AI 코어 {@link DefaultToolCallingChatOptions}(model·maxTokens·temperature·toolCallbacks·toolContext) 위에
 * Responses API 전용 4개 — {@code reasoning.effort}·{@code tool_choice}·{@code parallel_tool_calls}·{@code text.format} — 를 더한다.
 * {@code maxTokens} 가 {@code max_output_tokens}(추론 토큰 포함) 다. Spring AI OpenAI 모듈({@code OpenAiChatOptions})과 그 뒤의 공식 SDK 는 쓰지 않는다(2026-09-19).
 * <p>
 * <b>병합 규칙이 곧 배선 규칙이다.</b> Spring AI 2.0.1 의 ChatClient 는 요청 옵션을 "ChatModel 기본 옵션.mutate().combineWith(요청 customizer)" 로 만들고,
 * customizer 의 non-null 값만 기본 옵션을 덮는다. 그래서 (1) Builder 의 추가 필드 기본값은 전부 null 이어야 하고(false 같은 기본값은 base 를 덮는다),
 * (2) {@link #mutate()} 는 14개 필드를 전부 복사해야 한다(하나라도 빠지면 ChatClient 경로에서 그 값이 조용히 사라진다 — assist 호출이 judge 모델로 나가던
 * 2026-09-19 오배선과 같은 증상). Builder 구조는 {@code OpenAiChatOptions} 선례(self-type AbstractBuilder + 비제네릭 Builder)를 따른다.
 */
public class ResponsesChatOptions extends DefaultToolCallingChatOptions {

  private final String reasoningEffort;
  private final String toolChoice;
  private final Boolean parallelToolCalls;
  private final ResponsesTextFormat textFormat;

  protected ResponsesChatOptions(List<ToolCallback> toolCallbacks, Map<String, Object> toolContext, String model, Double frequencyPenalty,
      Integer maxTokens, Double presencePenalty, List<String> stopSequences, Double temperature, Integer topK, Double topP,
      String reasoningEffort, String toolChoice, Boolean parallelToolCalls, ResponsesTextFormat textFormat) {
    super(toolCallbacks, toolContext, model, frequencyPenalty, maxTokens, presencePenalty, stopSequences, temperature, topK, topP);
    this.reasoningEffort = reasoningEffort;
    this.toolChoice = toolChoice;
    this.parallelToolCalls = parallelToolCalls;
    this.textFormat = textFormat;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Responses {@code reasoning.effort} (none/minimal/low/medium/high/xhigh/max). null 이면 미전송 = 서버 기본 */
  public String getReasoningEffort() {
    return reasoningEffort;
  }

  /** Responses {@code tool_choice} 문자열형(auto/required/none). null 이면 미전송 */
  public String getToolChoice() {
    return toolChoice;
  }

  public Boolean getParallelToolCalls() {
    return parallelToolCalls;
  }

  /** 구조화 출력({@code text.format} json_schema). null 이면 자유 텍스트 */
  public ResponsesTextFormat getTextFormat() {
    return textFormat;
  }

  /**
   * 이 옵션의 값을 전부 실은 새 Builder. base 10개 + 추가 4개를 하나도 빠뜨리지 않는다(클래스 javadoc 참고).
   */
  @Override
  public Builder mutate() {
    return builder()
        .model(getModel())
        .frequencyPenalty(getFrequencyPenalty())
        .maxTokens(getMaxTokens())
        .presencePenalty(getPresencePenalty())
        .stopSequences(getStopSequences())
        .temperature(getTemperature())
        .topK(getTopK())
        .topP(getTopP())
        .toolCallbacks(getToolCallbacks())
        .toolContext(getToolContext())
        .reasoningEffort(reasoningEffort)
        .toolChoice(toolChoice)
        .parallelToolCalls(parallelToolCalls)
        .textFormat(textFormat);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!super.equals(o)) {
      return false;
    }
    ResponsesChatOptions that = (ResponsesChatOptions) o;
    return Objects.equals(reasoningEffort, that.reasoningEffort)
        && Objects.equals(toolChoice, that.toolChoice)
        && Objects.equals(parallelToolCalls, that.parallelToolCalls)
        && Objects.equals(textFormat, that.textFormat);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), reasoningEffort, toolChoice, parallelToolCalls, textFormat);
  }

  @Override
  public String toString() {
    return "ResponsesChatOptions{model=" + getModel() + ", maxTokens=" + getMaxTokens() + ", temperature=" + getTemperature()
        + ", reasoningEffort=" + reasoningEffort + ", toolChoice=" + toolChoice + ", parallelToolCalls=" + parallelToolCalls
        + ", textFormat=" + (textFormat == null ? null : textFormat.name())
        + ", toolCallbacks=" + (getToolCallbacks() == null ? 0 : getToolCallbacks().size()) + "}";
  }

  /**
   * self-type 빌더. Spring AI 기본 빌더의 protected 필드(model·maxTokens·toolCallbacks…)를 상속받고 추가 4필드를 얹는다.
   * {@code clone()} 은 추가 필드가 전부 불변이라 상위 구현(얕은 복사)으로 충분하다.
   */
  protected abstract static class AbstractBuilder<B extends AbstractBuilder<B>> extends DefaultToolCallingChatOptions.Builder<B> {

    protected String reasoningEffort;
    protected String toolChoice;
    protected Boolean parallelToolCalls;
    protected ResponsesTextFormat textFormat;

    public B reasoningEffort(String reasoningEffort) {
      this.reasoningEffort = reasoningEffort;
      return self();
    }

    public B toolChoice(String toolChoice) {
      this.toolChoice = toolChoice;
      return self();
    }

    public B parallelToolCalls(Boolean parallelToolCalls) {
      this.parallelToolCalls = parallelToolCalls;
      return self();
    }

    public B textFormat(ResponsesTextFormat textFormat) {
      this.textFormat = textFormat;
      return self();
    }

    /**
     * other 의 non-null 값만 덮는다. base 필드는 상위 구현이(toolCallbacks 는 이어붙이고 toolContext 는 putAll), 추가 4필드는 여기서.
     */
    @Override
    public B combineWith(ChatOptions.Builder<?> other) {
      super.combineWith(other);
      if (other instanceof AbstractBuilder<?> that) {
        if (that.reasoningEffort != null) {
          this.reasoningEffort = that.reasoningEffort;
        }
        if (that.toolChoice != null) {
          this.toolChoice = that.toolChoice;
        }
        if (that.parallelToolCalls != null) {
          this.parallelToolCalls = that.parallelToolCalls;
        }
        if (that.textFormat != null) {
          this.textFormat = that.textFormat;
        }
      }
      return self();
    }

    @Override
    public ResponsesChatOptions build() {
      return new ResponsesChatOptions(this.toolCallbacks, this.toolContext, this.model, this.frequencyPenalty, this.maxTokens, this.presencePenalty,
          this.stopSequences, this.temperature, this.topK, this.topP, this.reasoningEffort, this.toolChoice, this.parallelToolCalls, this.textFormat);
    }
  }

  public static class Builder extends AbstractBuilder<Builder> {
  }
}
