package kr.hvy.blog.modules.advisor.application.service;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesTextFormat;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesUsage;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 판단 모델 호출. 시스템 프롬프트는 정적 리소스, 사용자 메시지는 완성된 JSON 문자열, 출력은 strict JSON 스키마(후보 enum) 로 강제한다 —
 * Responses API {@code text.format}(json_schema, strict) 이고 스키마 이름은 응답 레코드 클래스명이다.
 * <p>
 * ChatClient 를 받아 만드는 얇은 래퍼라 테스트에서 가짜 HTTP 클라이언트 위의 진짜 Responses 모델로 대체할 수 있다. 운영에서는 AdvisorAiConfig 가
 * judge/assist 두 빈으로 등록하고 잡 생성자가 @Qualifier 로 받는다(잡에 생성자를 둘 두던 방식은 기동 실패, 2026-09-13).
 * 요청의 {@code .options(...)} 는 ChatClient 의 기본 옵션이 아니라 <b>ChatModel 의 기본 옵션</b> 위에 병합되므로 여기서는 text.format 만 넘기고
 * model·max_output_tokens 는 역할별 모델 빈이 정한다(AdvisorAiConfig 참고). 재시도는 OpenAiResponsesClient 가 담당하고, 여기서 예외가 나면 그날 판단을 건너뛴다(부분 추천 금지).
 */
@Slf4j
public class MarketJudgeClient {

  /** AdvisorAiConfig 가 등록하는 빈 이름 — 판단용(judge 모델) / 보조용(assist 모델). 잡 생성자의 @Qualifier 와 같은 상수를 쓴다 */
  public static final String JUDGE_BEAN = "judgeClient";
  public static final String ASSIST_BEAN = "assistClient";

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

  /** 임의 구조화 출력 호출 1건의 결과 */
  public record CallResult<T>(T value, String rawText, Usage usage, int reasoningTokens, int cachedTokens, String model, String responseId,
                              Map<String, Object> options) {
  }

  /**
   * 판단을 요청한다. schemaJson 은 그날 후보 티커가 enum 으로 박힌 스키마다.
   */
  public JudgeResult judge(String systemPrompt, PromptPayload payload, String schemaJson) {
    CallResult<AdviceResponse> r = call(systemPrompt, payload.json(), schemaJson, AdviceResponse.class);
    return new JudgeResult(r.value(), r.rawText(), r.usage(), r.reasoningTokens(), r.cachedTokens(), r.model(), r.responseId(), r.options());
  }

  /**
   * strict JSON 스키마로 구조화 출력을 받는 범용 호출 (교훈 제안·재현성 측정도 이 경로). JSON 파싱 전에 finishReason 을 먼저 본다 —
   * 출력 상한 절단(LENGTH)·기타 미완(INCOMPLETE)·거부(REFUSAL)를 파싱 실패로 뭉개지 않고 원인 그대로 예외에 싣는다.
   */
  public <T> CallResult<T> call(String systemPrompt, String userJson, String schemaJson, Class<T> type) {
    ResponsesTextFormat format = ResponsesTextFormat.strictJsonSchema(type.getSimpleName(), AdvisorJson.MAPPER.readTree(schemaJson));
    long started = System.currentTimeMillis();
    ChatResponse response = chatClient.prompt()
        .system(systemPrompt)
        .user(userJson)
        .options(ResponsesChatOptions.builder().textFormat(format))
        .call()
        .chatResponse();
    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
      throw new IllegalStateException("판단 모델이 빈 응답을 돌려줬습니다");
    }
    String text = response.getResult().getOutput().getText();
    String finishReason = OpenAiResponsesChatModel.finishReason(response);
    if (OpenAiResponsesChatModel.FINISH_LENGTH.equals(finishReason)) {
      throw new IllegalStateException("판단 모델 출력이 max_output_tokens 에서 잘렸습니다 — advisor.model.*-max-completion-tokens 를 확인하세요");
    }
    if (OpenAiResponsesChatModel.FINISH_INCOMPLETE.equals(finishReason)) {
      throw new IllegalStateException("판단 모델 응답이 미완입니다: " + OpenAiResponsesChatModel.incompleteReason(response));
    }
    if (OpenAiResponsesChatModel.FINISH_REFUSAL.equals(finishReason)) {
      throw new IllegalStateException("판단 모델이 응답을 거부했습니다: " + text);
    }
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("판단 모델 응답 본문이 비어 있습니다");
    }
    T parsed = AdvisorJson.read(text, type);
    ChatResponseMetadata metadata = response.getMetadata();
    Usage usage = metadata == null ? null : metadata.getUsage();
    String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank() ? modelHint : metadata.getModel();
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("model", model);
    options.put("responseFormat", "json_schema/strict");
    options.put("schemaName", format.name());
    options.put("schemaChars", schemaJson.length());
    options.put("latencyMs", System.currentTimeMillis() - started);
    log.info("LLM 구조화 호출: type={}, model={}, in={}, out={}, {}ms", type.getSimpleName(), model, usage == null ? null : usage.getPromptTokens(),
        usage == null ? null : usage.getCompletionTokens(), options.get("latencyMs"));
    return new CallResult<>(parsed, text, usage, reasoningTokens(usage), cachedTokens(usage), model,
        metadata == null ? null : metadata.getId(), options);
  }

  /**
   * Responses native usage({@link ResponsesUsage})의 추론 토큰 (없으면 0).
   */
  public static int reasoningTokens(Usage usage) {
    if (usage != null && usage.getNativeUsage() instanceof ResponsesUsage responses) {
      return responses.reasoningTokens();
    }
    return 0;
  }

  /**
   * Responses native usage 의 캐시 적중 입력 토큰 (없으면 0). native 가 없으면 Spring AI 가 라운드 합산해 주는 cacheReadInputTokens 로 대신한다.
   */
  public static int cachedTokens(Usage usage) {
    if (usage == null) {
      return 0;
    }
    if (usage.getNativeUsage() instanceof ResponsesUsage responses) {
      return responses.cachedTokens();
    }
    return usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens().intValue();
  }
}
