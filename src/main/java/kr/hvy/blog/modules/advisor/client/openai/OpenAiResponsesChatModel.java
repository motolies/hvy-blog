package kr.hvy.blog.modules.advisor.client.openai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.llm.LlmChatOptions;
import kr.hvy.blog.modules.advisor.client.llm.LlmJsonSchema;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.llm.LlmTokenUsage;
import kr.hvy.blog.modules.advisor.client.openai.dto.FunctionCallOutput;
import kr.hvy.blog.modules.advisor.client.openai.dto.FunctionTool;
import kr.hvy.blog.modules.advisor.client.openai.dto.InputMessage;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesTextFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI Responses API({@code /v1/responses})를 말하는 Spring AI {@link ChatModel}. advisor 의 OpenAI 호출은 전부 이 모델이다 — 채팅 봇(도구 루프)은
 * 2026-09-19 부터(GPT-5.4 이상은 Chat Completions 에서 도구 호출 시 reasoning_effort=none 만 허용해 400), judge/assist(strict JSON 구조화 출력)는
 * 그 뒤 {@code text.format} 매핑을 더해 옮겼고 그와 함께 Spring AI OpenAI 모듈·공식 SDK 의존성을 걷어냈다. HTTP 는 {@code openAiRestClient} 라
 * 호출 1건이 그대로 {@code tb_api_log} 1행이다.
 * <p>
 * <b>도구는 실행하지 않는다.</b> 2.0.1 의 도구 루프는 {@code ToolCallingAdvisor} 가 전담하므로 이 모델은 HTTP 1회 = 라운드 1회이고, function_call 을
 * {@code AssistantMessage.toolCalls} 로 돌려주면 어드바이저가 {@code @Tool} 을 부르고 {@code ToolResponseMessage} 를 붙여 다시 부른다.
 * <p>
 * 세션은 무상태({@code store=false})다 — 도구가 있을 때만 {@code include=reasoning.encrypted_content} 로 응답 {@code output} 원문(reasoning 암호화 블롭 포함)을
 * 받아 {@code AssistantMessage} 메타데이터에 실어 두면 {@code DefaultToolCallingManager} 가 같은 인스턴스를 히스토리에 붙이므로 다음 라운드 {@code input} 에
 * 그대로 되돌려 보낼 수 있다. 도구 없는 단발 호출(judge/assist)은 재전송이 없어 블롭을 받지 않는다(api_log 본문만 커진다).
 * <p>
 * 옵션은 {@link ResponsesChatOptions}. Spring AI 2.0.1 ChatClient 는 <b>이 모델의 기본 옵션</b>을 base 로 요청 customizer 를 병합하므로, 역할(judge/assist/chat)별로
 * 모델 인스턴스를 따로 두는 것이 옵션 배선의 정본이다(AdvisorAiConfig).
 * <p>
 * <b>공급자 중립 계약</b>(2026-11-11, Claude 1순위 → OpenAI 폴백): 이 모델은 폴백 래퍼 아래에서도 돈다. 그때 요청 옵션은 공급자 중립 {@link LlmChatOptions} 로
 * 도착하므로 구조화 출력 스키마·도구·도구 컨텍스트만 받고 model·출력 상한은 이 모델의 기본 옵션을 쓴다({@link #resolveOptions}). 응답에는
 * {@link LlmResponses} 의 의무대로 공급자 표시({@link LlmProvider#OPENAI})를 ChatResponseMetadata 와 AssistantMessage 메타데이터 양쪽에 싣는다.
 */
@Slf4j
public class OpenAiResponsesChatModel implements ChatModel {

  /** AssistantMessage 메타데이터 키: 이 라운드의 Responses output 원문(JsonNode 목록) — 다음 라운드 input 에 되돌려 보낸다 */
  public static final String OUTPUT_METADATA_KEY = "openai.responses.output";
  /** AssistantMessage 메타데이터 키: 이 라운드의 추론·캐시 토큰({@link LlmTokenUsage}) */
  public static final String ROUND_USAGE_METADATA_KEY = "openai.responses.usage";
  /** AssistantMessage 메타데이터 키: 이 턴(질문 1건)의 누적 추론·캐시 토큰({@link LlmTokenUsage}) */
  public static final String CUMULATIVE_USAGE_METADATA_KEY = "openai.responses.usage.cumulative";
  static final String INCLUDE_ENCRYPTED_REASONING = "reasoning.encrypted_content";
  static final String TOOL_CHOICE_NONE = "none";
  static final String SCHEMA_KEY = "$schema";
  static final String STATUS_FIELD = "status";
  private static final String EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

  private final OpenAiResponsesClient client;
  private final ResponsesChatOptions defaultOptions;
  private final int forceFinishAfterToolCalls;

  /**
   * @param forceFinishAfterToolCalls 프롬프트에 쌓인 도구 결과 수가 이 값 이상이면 {@code tool_choice=none} 으로 답을 강제한다(0 이면 끔).
   *                                  ToolCallingManager 의 총합 상한과 같은 값을 주면 상한 초과 뒤 모델이 오류 응답을 받고도 계속 도구를 부르는 무한 루프를 막는다
   */
  public OpenAiResponsesChatModel(OpenAiResponsesClient client, ResponsesChatOptions defaultOptions, int forceFinishAfterToolCalls) {
    this.client = client;
    this.defaultOptions = defaultOptions;
    this.forceFinishAfterToolCalls = forceFinishAfterToolCalls;
  }

  @Override
  public ResponsesChatOptions getOptions() {
    return defaultOptions;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    ResponsesChatOptions options = resolveOptions(prompt);
    ResponsesRequest request = toRequest(prompt.getInstructions(), options);
    ResponsesResponse response = client.create(request);
    return toChatResponse(response, prompt.getInstructions());
  }

  /**
   * 요청 옵션을 정한다.
   * <ol>
   *   <li>{@link ResponsesChatOptions} — ChatClient 가 이 모델 바로 위에 있을 때. 이 모델의 기본 옵션을 mutate·병합한 결과라 그대로 쓴다</li>
   *   <li>그 밖의 {@link LlmChatOptions}(하위 타입 포함 — 다른 공급자의 전용 옵션이 생겨도 같은 규칙) — 폴백 래퍼 경유. 래퍼의 기본 옵션이 공급자 중립이라
   *       요청도 그 타입으로 온다. 이 모델의 기본 옵션 위에
   *       {@code jsonSchema}·{@code toolCallbacks}·{@code toolContext} <b>세 값만</b> 얹고 model·maxTokens·temperature 등은 읽지 않는다 — 읽으면 다른 공급자의
   *       모델 ID(Claude)가 OpenAI 요청에 실릴 수 있다. 도구는 ChatClient 가 이 모델 위에 직접 있을 때와 같게 요청 것으로 바꾸고 컨텍스트는 합친다</li>
   *   <li>그 외 타입(스텁·직접 만든 Prompt) — 기본 옵션 위에 통째로 병합한다</li>
   * </ol>
   */
  ResponsesChatOptions resolveOptions(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    if (options == null) {
      return defaultOptions;
    }
    if (options instanceof ResponsesChatOptions responses) {
      return responses;
    }
    if (options instanceof LlmChatOptions neutral) {
      ResponsesChatOptions.Builder builder = defaultOptions.mutate();
      if (neutral.getJsonSchema() != null) {
        builder.jsonSchema(neutral.getJsonSchema());
      }
      if (neutral.getToolCallbacks() != null && !neutral.getToolCallbacks().isEmpty()) {
        builder.toolCallbacks(neutral.getToolCallbacks());
      }
      if (neutral.getToolContext() != null && !neutral.getToolContext().isEmpty()) {
        builder.toolContext(neutral.getToolContext());
      }
      return builder.build();
    }
    return defaultOptions.mutate().combineWith(options.mutate()).build();
  }

  /**
   * Spring AI 메시지 목록 → Responses 요청. 첫 SystemMessage 는 instructions, 이전 라운드 AssistantMessage 는 원문 재전송, ToolResponse 는 function_call_output.
   * 도구 관련 파라미터(tools·tool_choice·parallel_tool_calls·include)는 도구가 있을 때만, text.format 은 구조화 출력 옵션이 있을 때만 보낸다.
   */
  ResponsesRequest toRequest(List<Message> messages, ResponsesChatOptions options) {
    String instructions = null;
    List<Object> input = new ArrayList<>();
    int toolResponses = 0;
    for (Message message : messages) {
      switch (message.getMessageType()) {
        case SYSTEM -> {
          if (instructions == null) {
            instructions = message.getText();
          } else {
            input.add(new InputMessage(InputMessage.ROLE_DEVELOPER, StringUtils.defaultString(message.getText())));
          }
        }
        case USER -> input.add(new InputMessage(InputMessage.ROLE_USER, StringUtils.defaultString(message.getText())));
        case ASSISTANT -> input.addAll(assistantItems((AssistantMessage) message));
        case TOOL -> {
          for (ToolResponseMessage.ToolResponse toolResponse : ((ToolResponseMessage) message).getResponses()) {
            input.add(FunctionCallOutput.of(toolResponse.id(), toolResponse.responseData()));
            toolResponses++;
          }
        }
      }
    }
    List<FunctionTool> tools = functionTools(options.getToolCallbacks());
    boolean hasTools = !tools.isEmpty();
    boolean forceFinish = hasTools && forceFinishAfterToolCalls > 0 && toolResponses >= forceFinishAfterToolCalls;
    if (forceFinish) {
      log.warn("OpenAI responses: 도구 결과 {}건 ≥ 상한 {} — tool_choice=none 으로 답을 강제한다", toolResponses, forceFinishAfterToolCalls);
    }
    String effort = StringUtils.trimToNull(options.getReasoningEffort());
    return ResponsesRequest.builder()
        .model(options.getModel())
        .instructions(instructions)
        .input(input)
        .tools(hasTools ? tools : null)
        .toolChoice(!hasTools ? null : forceFinish ? TOOL_CHOICE_NONE : StringUtils.trimToNull(options.getToolChoice()))
        .parallelToolCalls(hasTools ? options.getParallelToolCalls() : null)
        .reasoning(effort == null ? null : new ResponsesRequest.Reasoning(effort.toLowerCase(Locale.ROOT)))
        .maxOutputTokens(options.getMaxTokens())
        .temperature(options.getTemperature())
        .text(options.getJsonSchema() == null ? null : ResponsesRequest.jsonSchema(textFormat(options.getJsonSchema())))
        .store(Boolean.FALSE)
        .include(hasTools ? List.of(INCLUDE_ENCRYPTED_REASONING) : null)
        .build();
  }

  /**
   * 공급자 중립 스키마 → Responses 와이어 형식({@code text.format} json_schema, strict). strict 의 전제(전 속성 required·additionalProperties=false)는 호출측 스키마 팩토리가 보장한다.
   */
  static ResponsesTextFormat textFormat(LlmJsonSchema schema) {
    return ResponsesTextFormat.strictJsonSchema(schema.name(), schema.schema());
  }

  /**
   * AssistantMessage → 입력 항목. 이 턴의 이전 라운드(메타데이터에 원문이 있음)는 원문을 {@code status} 만 빼고 그대로, Slack 히스토리처럼 원문이 없으면
   * 역할 메시지로, 원문 없는 도구 호출은 function_call 항목을 합성한다.
   */
  static List<Object> assistantItems(AssistantMessage message) {
    Object raw = message.getMetadata().get(OUTPUT_METADATA_KEY);
    if (raw instanceof List<?> items && !items.isEmpty()) {
      List<Object> replay = new ArrayList<>(items.size());
      for (Object item : items) {
        replay.add(item instanceof JsonNode node ? withoutStatus(node) : item);
      }
      return replay;
    }
    List<Object> out = new ArrayList<>();
    if (StringUtils.isNotBlank(message.getText())) {
      out.add(new InputMessage(InputMessage.ROLE_ASSISTANT, message.getText()));
    }
    for (AssistantMessage.ToolCall call : message.getToolCalls()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("type", ResponsesResponse.ITEM_FUNCTION_CALL);
      item.put("call_id", call.id());
      item.put("name", call.name());
      item.put("arguments", StringUtils.defaultIfBlank(call.arguments(), "{}"));
      out.add(item);
    }
    return out;
  }

  /**
   * 응답 항목의 {@code status}(completed 등)는 입력으로 되돌릴 때 빼야 한다(OpenAI 예제와 동일). 원본은 건드리지 않는다.
   */
  static JsonNode withoutStatus(JsonNode node) {
    if (node instanceof ObjectNode object && !object.path(STATUS_FIELD).isMissingNode()) {
      ObjectNode copy = object.deepCopy();
      copy.remove(STATUS_FIELD);
      return copy;
    }
    return node;
  }

  /**
   * ToolCallback → function 도구 정의. 스키마는 Spring AI 생성 원문에서 루트 {@code $schema} 만 뺀다.
   */
  public static List<FunctionTool> functionTools(List<ToolCallback> callbacks) {
    if (callbacks == null || callbacks.isEmpty()) {
      return List.of();
    }
    List<FunctionTool> tools = new ArrayList<>(callbacks.size());
    for (ToolCallback callback : callbacks) {
      ToolDefinition definition = callback.getToolDefinition();
      tools.add(FunctionTool.function(definition.name(), definition.description(), parameters(definition.inputSchema())));
    }
    return tools;
  }

  static JsonNode parameters(String inputSchema) {
    JsonNode schema = AdvisorJson.MAPPER.readTree(StringUtils.isBlank(inputSchema) ? EMPTY_OBJECT_SCHEMA : inputSchema);
    if (schema instanceof ObjectNode object) {
      object.remove(SCHEMA_KEY);
    }
    return schema;
  }

  /**
   * Responses 응답 → ChatResponse. 텍스트는 message 항목의 output_text 결합, function_call 은 toolCalls(id = call_id), 원문·사용량은 메타데이터.
   * output_text 없이 refusal 파트만 있으면 텍스트는 거부 문구·finishReason 은 REFUSAL, incomplete 는 사유가 max_output_tokens 면 LENGTH 아니면 INCOMPLETE.
   * 공급자 표시는 응답 메타데이터(호출부 기록용)와 AssistantMessage 메타데이터(도구 루프 히스토리에서 "이 턴을 누가 만들었나" 판별용) 양쪽에 싣는다.
   */
  ChatResponse toChatResponse(ResponsesResponse response, List<Message> instructions) {
    if (response.isFailed()) {
      throw new OpenAiResponsesException(0, response.error() == null ? null : response.error().code(),
          "OpenAI 응답 status=failed: " + StringUtils.defaultString(response.reason()), false, null);
    }
    StringBuilder text = new StringBuilder();
    StringBuilder refusal = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    for (JsonNode item : response.output()) {
      String type = item.path("type").asString();
      if (ResponsesResponse.ITEM_MESSAGE.equals(type)) {
        JsonNode contents = item.path("content");
        for (int i = 0; i < contents.size(); i++) {
          JsonNode content = contents.path(i);
          String contentType = content.path("type").asString();
          if (ResponsesResponse.CONTENT_OUTPUT_TEXT.equals(contentType)) {
            appendLine(text, content.path("text").asString());
          } else if (ResponsesResponse.CONTENT_REFUSAL.equals(contentType)) {
            appendLine(refusal, content.path(ResponsesResponse.CONTENT_REFUSAL).asString());
          }
        }
      } else if (ResponsesResponse.ITEM_FUNCTION_CALL.equals(type)) {
        toolCalls.add(new AssistantMessage.ToolCall(item.path("call_id").asString(), FunctionTool.TYPE_FUNCTION, item.path("name").asString(),
            item.path("arguments").asString()));
      }
    }
    boolean refused = text.isEmpty() && !refusal.isEmpty();
    if (refused) {
      log.warn("OpenAI 응답 refusal: id={}, refusal={}", response.id(), refusal);
    }
    if (response.isIncomplete()) {
      log.warn("OpenAI 응답 incomplete: reason={}, id={}, toolCalls={}", response.incompleteReason(), response.id(), toolCalls.size());
    }

    ResponsesResponse.Usage usage = response.usage();
    LlmTokenUsage round = usage == null ? LlmTokenUsage.ZERO : new LlmTokenUsage(usage.reasoningTokens(), usage.cachedTokens());
    LlmTokenUsage cumulative = priorUsage(instructions).plus(round);

    Map<String, Object> properties = new LinkedHashMap<>();
    if (response.id() != null) {
      properties.put("id", response.id());
    }
    if (response.model() != null) {
      properties.put("model", response.model());
    }
    properties.put(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.OPENAI.name());
    properties.put(OUTPUT_METADATA_KEY, response.output());
    properties.put(ROUND_USAGE_METADATA_KEY, round);
    properties.put(CUMULATIVE_USAGE_METADATA_KEY, cumulative);

    AssistantMessage assistant = AssistantMessage.builder()
        .content(refused ? refusal.toString() : text.toString())
        .properties(properties)
        .toolCalls(toolCalls)
        .media(List.of())
        .build();
    Generation generation = new Generation(assistant, ChatGenerationMetadata.builder().finishReason(finishReason(response, toolCalls, refused)).build());

    // 캐시 토큰은 Spring AI 가 라운드 합산해 주는 cacheReadInputTokens 슬롯으로도 싣는다(native usage 는 합산 시 버려진다)
    Usage springUsage = usage == null
        ? new DefaultUsage(0, 0, 0, cumulative)
        : new DefaultUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), cumulative, (long) usage.cachedTokens(), null);
    ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
        .id(StringUtils.defaultString(response.id()))
        .model(StringUtils.defaultString(response.model()))
        .usage(springUsage)
        .keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.OPENAI.name());
    if (response.isIncomplete()) {
      metadata.keyValue(LlmResponses.INCOMPLETE_REASON_METADATA_KEY, StringUtils.defaultString(response.incompleteReason()));
    }
    return new ChatResponse(List.of(generation), metadata.build());
  }

  private static void appendLine(StringBuilder target, String line) {
    if (!target.isEmpty()) {
      target.append('\n');
    }
    target.append(line);
  }

  private static String finishReason(ResponsesResponse response, List<AssistantMessage.ToolCall> toolCalls, boolean refused) {
    if (!toolCalls.isEmpty()) {
      return LlmResponses.FINISH_TOOL_CALLS;
    }
    if (refused) {
      return LlmResponses.FINISH_REFUSAL;
    }
    if (response.isIncomplete()) {
      return ResponsesResponse.INCOMPLETE_MAX_OUTPUT_TOKENS.equals(response.incompleteReason()) ? LlmResponses.FINISH_LENGTH : LlmResponses.FINISH_INCOMPLETE;
    }
    return LlmResponses.FINISH_STOP;
  }

  /**
   * 프롬프트에 든 이 턴의 이전 라운드 AssistantMessage 들의 라운드 사용량 합. Slack 히스토리 메시지는 메타데이터가 없어 세지 않는다.
   */
  static LlmTokenUsage priorUsage(List<Message> instructions) {
    LlmTokenUsage total = LlmTokenUsage.ZERO;
    for (Message message : instructions) {
      if (message instanceof AssistantMessage && message.getMetadata().get(ROUND_USAGE_METADATA_KEY) instanceof LlmTokenUsage roundUsage) {
        total = total.plus(roundUsage);
      }
    }
    return total;
  }

  /**
   * 최종 ChatResponse 에서 이 턴 누적 추론·캐시 토큰을 읽는다. Spring AI 의 라운드 합산은 native usage 를 버리므로 메타데이터로 나른다.
   * Responses 모델이 만든 응답이 아니면 empty.
   */
  public static Optional<LlmTokenUsage> cumulativeUsage(ChatResponse response) {
    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
      return Optional.empty();
    }
    Object value = response.getResult().getOutput().getMetadata().get(CUMULATIVE_USAGE_METADATA_KEY);
    return value instanceof LlmTokenUsage usage ? Optional.of(usage) : Optional.empty();
  }
}
