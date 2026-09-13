package kr.hvy.blog.modules.advisor.application.service;

import com.openai.models.completions.CompletionUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 판단 모델 호출. 시스템 프롬프트는 정적 리소스, 사용자 메시지는 완성된 JSON 문자열, 출력은 strict JSON 스키마(후보 enum) 로 강제한다.
 * <p>
 * Spring 빈이 아니라 ChatClient 를 받아 만드는 얇은 래퍼라 테스트에서 ChatModel 스텁으로 대체할 수 있다. 재시도는 OpenAI SDK 가 담당하고,
 * 여기서 예외가 나면 그날 판단을 건너뛴다(부분 추천 금지).
 */
@Slf4j
public class MarketJudgeClient {

  /** 호출 1건의 결과: 파싱된 응답 + 원문 + 사용량 */
  public record JudgeResult(AdviceResponse response, String rawText, Usage usage, int reasoningTokens, int cachedTokens, String model,
                            String responseId, Map<String, Object> options) {
  }

  private final ChatClient chatClient;
  private final String modelHint;

  public MarketJudgeClient(ChatClient chatClient, String modelHint) {
    this.chatClient = chatClient;
    this.modelHint = modelHint;
  }

  /**
   * 판단을 요청한다. schemaJson 은 그날 후보 티커가 enum 으로 박힌 스키마다.
   */
  public JudgeResult judge(String systemPrompt, PromptPayload payload, String schemaJson) {
    OpenAiChatModel.ResponseFormat format = OpenAiChatModel.ResponseFormat.builder()
        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
        .jsonSchema(schemaJson)
        .strict(true)
        .build();
    long started = System.currentTimeMillis();
    ChatResponse response = chatClient.prompt()
        .system(systemPrompt)
        .user(payload.json())
        .options(OpenAiChatOptions.builder().responseFormat(format))
        .call()
        .chatResponse();
    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
      throw new IllegalStateException("판단 모델이 빈 응답을 돌려줬습니다");
    }
    String text = response.getResult().getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("판단 모델 응답 본문이 비어 있습니다");
    }
    AdviceResponse parsed = AdvisorJson.read(text, AdviceResponse.class);
    ChatResponseMetadata metadata = response.getMetadata();
    Usage usage = metadata == null ? null : metadata.getUsage();
    String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank() ? modelHint : metadata.getModel();
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("model", model);
    options.put("responseFormat", "json_schema/strict");
    options.put("schemaChars", schemaJson.length());
    options.put("latencyMs", System.currentTimeMillis() - started);
    log.info("판단 모델 호출: model={}, in={}, out={}, {}ms", model, usage == null ? null : usage.getPromptTokens(),
        usage == null ? null : usage.getCompletionTokens(), options.get("latencyMs"));
    return new JudgeResult(parsed, text, usage, reasoningTokens(usage), cachedTokens(usage), model,
        metadata == null ? null : metadata.getId(), options);
  }

  /**
   * OpenAI native usage 의 추론 토큰 (없으면 0).
   */
  static int reasoningTokens(Usage usage) {
    if (usage != null && usage.getNativeUsage() instanceof CompletionUsage native_) {
      return native_.completionTokensDetails().flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens).map(Long::intValue).orElse(0);
    }
    return 0;
  }

  /**
   * OpenAI native usage 의 캐시 적중 입력 토큰 (없으면 0).
   */
  static int cachedTokens(Usage usage) {
    if (usage != null && usage.getNativeUsage() instanceof CompletionUsage native_) {
      return native_.promptTokensDetails().flatMap(CompletionUsage.PromptTokensDetails::cachedTokens).map(Long::intValue).orElse(0);
    }
    return 0;
  }
}
