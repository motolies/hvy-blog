package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 공급자 중립 요청 옵션. Spring AI 코어 {@link DefaultToolCallingChatOptions}(model·maxTokens·toolCallbacks·toolContext …) 위에 구조화 출력 스키마
 * ({@link LlmJsonSchema}) 하나를 더한다. 호출부({@code MarketJudgeClient})는 이 타입으로만 옵션을 넘기고, {@link FallbackChatModel} 의 기본 옵션도 이 타입이다.
 * <p>
 * <b>요청 수준에서 읽는 값은 jsonSchema·toolCallbacks·toolContext 뿐이다.</b> model·maxTokens 는 공급자별 ChatModel 의 기본 옵션이 소유한다 —
 * 래퍼의 기본 옵션이나 요청 옵션에 model 을 넣으면 Claude 모델 ID 가 OpenAI 요청에 실리는 식으로 공급자를 넘어 샌다. 그래서 공급자 모델은
 * 이 타입을 받으면 세 값만 자기 기본 옵션 위에 얹는다.
 * <p>
 * <b>병합 규칙이 곧 배선 규칙이다</b>(ResponsesChatOptions 와 같은 계약). Spring AI 2.0.1 ChatClient 는 요청 옵션을
 * "ChatModel 기본 옵션.mutate().combineWith(요청 customizer)" 로 만든다. 그래서 (1) Builder 의 추가 필드 기본값은 null, (2) {@link #mutate()} 는
 * 11개 필드를 전부 복사, (3) {@code build()} 를 오버라이드해 이 타입을 만들어야 한다(안 하면 DefaultToolCallingChatOptions 가 나와 스키마가 조용히 사라진다).
 * 공급자 전용 옵션은 이 클래스를 상속한다({@code ResponsesChatOptions}).
 */
public class LlmChatOptions extends DefaultToolCallingChatOptions {

  private final LlmJsonSchema jsonSchema;

  protected LlmChatOptions(List<ToolCallback> toolCallbacks, Map<String, Object> toolContext, String model, Double frequencyPenalty,
      Integer maxTokens, Double presencePenalty, List<String> stopSequences, Double temperature, Integer topK, Double topP,
      LlmJsonSchema jsonSchema) {
    super(toolCallbacks, toolContext, model, frequencyPenalty, maxTokens, presencePenalty, stopSequences, temperature, topK, topP);
    this.jsonSchema = jsonSchema;
  }

  /**
   * 반환형이 구체 {@link Builder} 가 아니라 {@link AbstractBuilder} 인 것은 하위 옵션이 자기 {@code builder()} 를 선언할 수 있게 하기 위해서다 —
   * 정적 메서드 숨김도 반환형이 대체 가능해야 해서(JLS 8.4.8.3), 구체 타입을 돌려주면 하위 클래스의 {@code builder()} 가 컴파일되지 않는다
   * (Spring AI 의 {@code DefaultToolCallingChatOptions.builder()} 가 {@code Builder<?>} 인 것과 같은 이유).
   */
  public static AbstractBuilder<?> builder() {
    return new Builder();
  }

  /** 구조화 출력 스키마. null 이면 자유 텍스트 */
  public LlmJsonSchema getJsonSchema() {
    return jsonSchema;
  }

  /**
   * 이 옵션의 값을 전부 실은 새 Builder. base 10개 + jsonSchema 를 하나도 빠뜨리지 않는다(클래스 javadoc 참고).
   * 반환형이 {@link AbstractBuilder} 인 것은 하위 클래스가 자기 Builder 로 공변 오버라이드할 수 있게 하기 위해서다.
   */
  @Override
  public AbstractBuilder<?> mutate() {
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
        .jsonSchema(jsonSchema);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!super.equals(o)) {
      return false;
    }
    return Objects.equals(jsonSchema, ((LlmChatOptions) o).jsonSchema);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), jsonSchema);
  }

  @Override
  public String toString() {
    return "LlmChatOptions{model=" + getModel() + ", maxTokens=" + getMaxTokens()
        + ", jsonSchema=" + (jsonSchema == null ? null : jsonSchema.name())
        + ", toolCallbacks=" + (getToolCallbacks() == null ? 0 : getToolCallbacks().size()) + "}";
  }

  /**
   * self-type 빌더. Spring AI 기본 빌더의 protected 필드(model·maxTokens·toolCallbacks…)를 상속받고 jsonSchema 를 얹는다.
   * {@code clone()} 은 추가 필드가 불변이라 상위 구현(얕은 복사)으로 충분하다. 하위 옵션의 빌더가 상속하므로 public 이다.
   */
  public abstract static class AbstractBuilder<B extends AbstractBuilder<B>> extends DefaultToolCallingChatOptions.Builder<B> {

    protected LlmJsonSchema jsonSchema;

    /**
     * 구조화 출력 스키마를 싣는다. null 이면 미설정으로 남아 병합 때 base 값을 덮지 않는다.
     */
    public B jsonSchema(LlmJsonSchema jsonSchema) {
      this.jsonSchema = jsonSchema;
      return self();
    }

    /**
     * other 의 non-null 값만 덮는다. base 필드는 상위 구현이(toolCallbacks·toolContext 포함), jsonSchema 는 여기서.
     */
    @Override
    public B combineWith(ChatOptions.Builder<?> other) {
      super.combineWith(other);
      if (other instanceof AbstractBuilder<?> that && that.jsonSchema != null) {
        this.jsonSchema = that.jsonSchema;
      }
      return self();
    }

    /**
     * base 10개 + jsonSchema 로 옵션을 만든다.
     */
    @Override
    public LlmChatOptions build() {
      return new LlmChatOptions(this.toolCallbacks, this.toolContext, this.model, this.frequencyPenalty, this.maxTokens, this.presencePenalty,
          this.stopSequences, this.temperature, this.topK, this.topP, this.jsonSchema);
    }
  }

  public static class Builder extends AbstractBuilder<Builder> {
  }
}
