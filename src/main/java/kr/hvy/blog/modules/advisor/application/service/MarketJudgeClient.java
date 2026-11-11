package kr.hvy.blog.modules.advisor.application.service;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.hvy.blog.modules.advisor.client.llm.AdviceResponse;
import kr.hvy.blog.modules.advisor.client.llm.LlmChatOptions;
import kr.hvy.blog.modules.advisor.client.llm.LlmJsonSchema;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.llm.LlmTokenUsage;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 판단 모델 호출. 시스템 프롬프트는 정적 리소스, 사용자 메시지는 완성된 JSON 문자열, 출력은 strict JSON 스키마(후보 enum) 로 강제한다 —
 * 스키마는 공급자 중립 {@link LlmJsonSchema} 로 넘기고 응답하는 공급자 모델이 자기 요청 형식으로 옮긴다(OpenAI {@code text.format}, Claude {@code output_config.format}).
 * 스키마 이름은 응답 레코드 클래스명이다.
 * <p>
 * <b>공급자 구현 패키지({@code client.openai}·{@code client.claude})에 의존하지 않는다</b>(2026-11-11). 옵션은 {@link LlmChatOptions}, 종료 사유·공급자 표시는
 * {@link LlmResponses}, 추론·캐시 토큰은 {@link LlmTokenUsage} 로만 읽는다 — ChatClient 가 폴백 래퍼(Claude 1순위 → OpenAI) 위에 올라가도 이 클래스는 그대로다.
 * <p>
 * ChatClient 를 받아 만드는 얇은 래퍼라 테스트에서 가짜 HTTP 클라이언트 위의 진짜 공급자 모델로 대체할 수 있다. 운영에서는 AdvisorAiConfig 가
 * judge/assist 두 빈으로 등록하고 잡 생성자가 @Qualifier 로 받는다(잡에 생성자를 둘 두던 방식은 기동 실패, 2026-09-13).
 * 요청의 {@code .options(...)} 는 ChatClient 의 기본 옵션이 아니라 <b>ChatModel 의 기본 옵션</b> 위에 병합되므로 여기서는 스키마만 넘기고
 * model·출력 토큰 상한은 역할별 공급자 모델 빈이 정한다(AdvisorAiConfig 참고). 재시도·폴백은 공급자 클라이언트와 폴백 래퍼가 담당하고, 여기서 예외가 나면 그날 판단을 건너뛴다(부분 추천 금지).
 */
@Slf4j
public class MarketJudgeClient {

  /** AdvisorAiConfig 가 등록하는 빈 이름 — 판단용(judge 모델) / 보조용(assist 모델). 잡 생성자의 @Qualifier 와 같은 상수를 쓴다 */
  public static final String JUDGE_BEAN = "judgeClient";
  public static final String ASSIST_BEAN = "assistClient";

  /**
   * 호출 1건의 결과: 파싱된 응답 + 원문 + 사용량. provider 는 이 응답을 만든 공급자 — 공급자 표시가 없는 응답(테스트 스텁)이면 null.
   * 잡은 이 값으로 비용 단가를 고르고, LIVE 와 섀도·재현성 재실행이 같은 공급자였는지 가린다.
   */
  public record JudgeResult(AdviceResponse response, String rawText, Usage usage, int reasoningTokens, int cachedTokens, String model,
                            String responseId, Map<String, Object> options, LlmProvider provider) {
  }

  private final ChatClient chatClient;
  private final String modelHint;

  public MarketJudgeClient(ChatClient chatClient, String modelHint) {
    this.chatClient = chatClient;
    this.modelHint = modelHint;
  }

  /** 임의 구조화 출력 호출 1건의 결과. provider 는 {@link JudgeResult} 와 같다(공급자 표시가 없으면 null) */
  public record CallResult<T>(T value, String rawText, Usage usage, int reasoningTokens, int cachedTokens, String model, String responseId,
                              Map<String, Object> options, LlmProvider provider) {
  }

  /**
   * 판단을 요청한다. schemaJson 은 그날 후보 티커가 enum 으로 박힌 스키마다.
   */
  public JudgeResult judge(String systemPrompt, PromptPayload payload, String schemaJson) {
    CallResult<AdviceResponse> r = call(systemPrompt, payload.json(), schemaJson, AdviceResponse.class);
    return new JudgeResult(r.value(), r.rawText(), r.usage(), r.reasoningTokens(), r.cachedTokens(), r.model(), r.responseId(), r.options(),
        r.provider());
  }

  /**
   * strict JSON 스키마로 구조화 출력을 받는 범용 호출 (교훈 제안·재현성 측정도 이 경로). JSON 파싱 전에 finishReason 을 먼저 본다 —
   * 출력 상한 절단(LENGTH)·기타 미완(INCOMPLETE)·거부(REFUSAL)를 파싱 실패로 뭉개지 않고 원인 그대로 예외에 싣는다.
   * <p>
   * options 맵은 {@code tb_advisor_prompt_input.options_json} 으로 저장된다. 어느 공급자가 답했는지({@code provider})와 1순위 대신 폴백이 답한
   * 사유({@code fallbackReason})를 여기에 남겨야 사후에 판단 주체를 가를 수 있다 — 표시가 없는 응답이면 키 자체를 넣지 않는다.
   */
  public <T> CallResult<T> call(String systemPrompt, String userJson, String schemaJson, Class<T> type) {
    LlmJsonSchema schema = new LlmJsonSchema(type.getSimpleName(), AdvisorJson.MAPPER.readTree(schemaJson));
    long started = System.currentTimeMillis();
    ChatResponse response = chatClient.prompt()
        .system(systemPrompt)
        .user(userJson)
        .options(LlmChatOptions.builder().jsonSchema(schema))
        .call()
        .chatResponse();
    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
      throw new IllegalStateException("판단 모델이 빈 응답을 돌려줬습니다");
    }
    String text = response.getResult().getOutput().getText();
    LlmProvider provider = LlmResponses.provider(response);
    String finishReason = LlmResponses.finishReason(response);
    if (LlmResponses.FINISH_LENGTH.equals(finishReason)) {
      throw new IllegalStateException("판단 모델 출력이 출력 토큰 상한에서 잘렸습니다" + (provider == null ? "" : "(공급자 " + provider.name() + ")")
          + " — advisor.model.*-max-completion-tokens(OpenAI) / advisor.claude.*-max-tokens(Claude) 를 확인하세요");
    }
    if (LlmResponses.FINISH_INCOMPLETE.equals(finishReason)) {
      throw new IllegalStateException("판단 모델 응답이 미완입니다: " + LlmResponses.incompleteReason(response));
    }
    if (LlmResponses.FINISH_REFUSAL.equals(finishReason)) {
      throw new IllegalStateException("판단 모델이 응답을 거부했습니다: " + text);
    }
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("판단 모델 응답 본문이 비어 있습니다");
    }
    T parsed = AdvisorJson.read(text, type);
    ChatResponseMetadata metadata = response.getMetadata();
    Usage usage = metadata == null ? null : metadata.getUsage();
    String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank() ? modelHint : metadata.getModel();
    String fallbackReason = LlmResponses.fallbackReason(response);
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("model", model);
    if (provider != null) {
      options.put("provider", provider.name());
    }
    if (fallbackReason != null) {
      options.put("fallbackReason", fallbackReason);
    }
    options.put("responseFormat", "json_schema/strict");
    options.put("schemaName", schema.name());
    options.put("schemaChars", schemaJson.length());
    options.put("latencyMs", System.currentTimeMillis() - started);
    log.info("LLM 구조화 호출: type={}, provider={}, model={}, fallbackReason={}, in={}, out={}, {}ms", type.getSimpleName(), provider, model,
        fallbackReason, usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(), options.get("latencyMs"));
    return new CallResult<>(parsed, text, usage, reasoningTokens(usage), cachedTokens(usage), model,
        metadata == null ? null : metadata.getId(), options, provider);
  }

  /**
   * 공급자 native usage({@link LlmTokenUsage})의 추론 토큰 (없으면 0). OpenAI·Claude 모델 모두 이 record 를 native usage 로 싣는다.
   */
  public static int reasoningTokens(Usage usage) {
    if (usage != null && usage.getNativeUsage() instanceof LlmTokenUsage details) {
      return details.reasoningTokens();
    }
    return 0;
  }

  /**
   * 공급자 native usage 의 캐시 적중 입력 토큰 (없으면 0). native 가 없으면 Spring AI 가 라운드 합산해 주는 cacheReadInputTokens 로 대신한다.
   */
  public static int cachedTokens(Usage usage) {
    if (usage == null) {
      return 0;
    }
    if (usage.getNativeUsage() instanceof LlmTokenUsage details) {
      return details.cachedTokens();
    }
    return usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens().intValue();
  }
}
