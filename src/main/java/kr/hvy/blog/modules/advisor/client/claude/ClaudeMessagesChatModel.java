package kr.hvy.blog.modules.advisor.client.claude;

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.Usage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.llm.LlmChatOptions;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmJsonSchema;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.llm.LlmTokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Messages API({@code /v1/messages})를 말하는 Spring AI {@link ChatModel}. advisor 의 1순위 공급자이고(2026-11-11) 실패하면
 * {@code FallbackChatModel} 이 {@code OpenAiResponsesChatModel} 로 넘긴다. HTTP·재시도·오류 분류는 {@link ClaudeMessagesClient} 가 맡고,
 * 이 클래스는 Spring AI {@link Prompt} ⇄ SDK {@link MessageCreateParams}/{@link Message} 변환만 한다.
 * <p>
 * <b>도구는 실행하지 않는다.</b> OpenAI 모델과 같은 계약이다 — HTTP 1회 = 라운드 1회이고, tool_use 를 {@code AssistantMessage.toolCalls} 로 돌려주면
 * {@code ToolCallingAdvisor} 가 {@code @Tool} 을 부르고 {@code ToolResponseMessage} 를 붙여 다시 부른다.
 * <p>
 * <b>이력은 덧붙이기만 한다.</b> 대상 모델은 thinking 이 항상 켜져 있고, thinking 블록의 signature 는 그 블록이 만들어질 때의 system·도구 집합·앞선 메시지에
 * 묶인다(바뀐 이력으로 블록을 되돌려 보내면 400). 그래서 (1) 응답 {@link Message} 원본을 {@code AssistantMessage} 메타데이터에 실어 다음 라운드에 한 글자도
 * 바꾸지 않고 되돌려 보내고, (2) 메시지 변환(같은 role 병합·선두 보정)은 같은 입력에 항상 같은 출력을 내며, (3) 도구는 이름순으로 고정한다 — signature 는
 * 도구의 집합만 보지만 프롬프트 캐시는 바이트 접두라 순서까지 같아야 맞는다. {@code tool_choice}·{@code output_config}·{@code cache_control} 은
 * 그 검사 밖이라 라운드마다 달라도 된다.
 * <p>
 * <b>model·max_tokens 는 {@link ClaudeModelSettings} 에서만 온다.</b> 프롬프트 옵션에서 읽는 것은 구조화 출력 스키마와 도구 콜백뿐이다 — 요청 옵션의 model 을
 * 따르면 폴백 래퍼를 거쳐 온 다른 공급자의 모델 ID 가 Claude 요청에 실린다. 같은 이유로 {@link #getOptions()} 는 빈 옵션이다.
 * {@code temperature}·{@code thinking} 은 보내지 않는다(대상 모델은 둘 다 400).
 */
@Slf4j
public class ClaudeMessagesChatModel implements ChatModel {

  /** AssistantMessage 메타데이터 키: 이 라운드의 SDK {@link Message} 원본 — 다음 라운드에 그대로 되돌려 보낸다 */
  public static final String MESSAGE_METADATA_KEY = "claude.messages.message";

  /** 첫 메시지가 assistant 일 때 그 앞에 넣는 고정 user 메시지. 라운드마다 같아야 하므로 상수다 */
  static final String LEADING_USER_TEXT = "(대화 시작)";
  /** stop_reason 이 없는 응답의 미완 사유 */
  static final String UNKNOWN_STOP_REASON = "unknown";
  static final String TOOL_CALL_TYPE = "function";
  static final String SCHEMA_KEY = "$schema";
  static final String TYPE_KEY = "type";
  private static final String SYSTEM_SEPARATOR = "\n\n";
  private static final String EMPTY_OBJECT = "{}";
  /** tool_use 입력 직렬화 전용. {@code AdvisorJson.MAPPER} 는 null 값을 가진 키를 지우므로(프롬프트 토큰 절약용) 모델이 준 입력을 그대로 옮기지 못한다 */
  private static final JsonMapper TOOL_INPUT_MAPPER = JsonMapper.builder().build();

  private final ClaudeMessagesClient client;
  private final ClaudeModelSettings settings;
  private final LlmChatOptions defaultOptions = LlmChatOptions.builder().build();

  /**
   * @param client   Claude Messages 클라이언트(재시도·오류 분류 담당). 역할에 맞는 것을 넘긴다 — 채팅은 타임아웃이 짧은 채팅용 클라이언트
   * @param settings 이 역할의 모델 ID·출력 상한·effort·캐싱·강제 마무리 설정. 요청 옵션이 아니라 여기 값이 요청에 실린다
   */
  public ClaudeMessagesChatModel(ClaudeMessagesClient client, ClaudeModelSettings settings) {
    this.client = client;
    this.settings = settings;
  }

  /**
   * 빈 옵션. ChatClient 는 이 값을 base 로 요청 옵션을 병합하는데, 여기에 model·maxTokens 를 실으면 폴백 래퍼를 거쳐 다른 공급자 요청으로 샌다.
   */
  @Override
  public LlmChatOptions getOptions() {
    return defaultOptions;
  }

  /**
   * 프롬프트 1건을 Messages 요청으로 바꿔 보내고 응답을 Spring AI 형태로 돌려준다(비스트리밍). 도구 실행은 하지 않는다 — tool_use 를 돌려주면
   * ChatClient 의 ToolCallingAdvisor 가 실행한 뒤 이 메서드를 다시 부른다.
   *
   * @throws kr.hvy.blog.modules.advisor.client.llm.LlmProviderException 호출 실패(분류 포함) — 폴백 여부는 폴백 래퍼가 정한다
   */
  @Override
  public ChatResponse call(Prompt prompt) {
    MessageCreateParams params = toParams(prompt.getInstructions(), prompt.getOptions());
    Message message = client.create(params);
    return toChatResponse(message);
  }

  /**
   * Spring AI 메시지 목록 → Messages 요청. SystemMessage 는 전부 이어 {@code system} 으로, 이 모델이 만든 AssistantMessage 는 원본 재전송,
   * 그 밖의 메시지는 블록으로 합성한다. 도구·tool_choice 는 도구가 있을 때만, output_config 는 스키마나 effort 가 있을 때만 보낸다.
   *
   * @throws LlmProviderException 모델 ID 가 비었으면 MISCONFIGURED(폴백 대상), 보낼 메시지가 없으면 REQUEST_INVALID
   */
  MessageCreateParams toParams(List<org.springframework.ai.chat.messages.Message> messages, ChatOptions options) {
    if (StringUtils.isBlank(settings.model())) {
      throw new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.MISCONFIGURED, 0, null, "Claude 모델 ID 가 비어 있습니다", null);
    }
    List<String> system = new ArrayList<>();
    Conversation conversation = new Conversation();
    int toolResponses = 0;
    for (org.springframework.ai.chat.messages.Message message : messages) {
      switch (message.getMessageType()) {
        case SYSTEM -> {
          if (StringUtils.isNotBlank(message.getText())) {
            system.add(message.getText());
          }
        }
        case USER -> conversation.user(textBlocks(message.getText()));
        case ASSISTANT -> {
          AssistantMessage assistant = (AssistantMessage) message;
          if (assistant.getMetadata().get(MESSAGE_METADATA_KEY) instanceof Message original) {
            conversation.replay(original);
          } else {
            conversation.assistant(assistantBlocks(assistant));
          }
        }
        case TOOL -> {
          List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) message).getResponses();
          conversation.user(toolResultBlocks(responses));
          toolResponses += responses.size();
        }
      }
    }
    List<MessageParam> turns = conversation.build();
    if (turns.isEmpty()) {
      throw new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.REQUEST_INVALID, 0, null, "보낼 user·assistant 메시지가 없습니다", null);
    }

    MessageCreateParams.Builder params = MessageCreateParams.builder()
        .model(settings.model())
        .maxTokens(settings.maxTokens())
        .messages(turns);
    if (!system.isEmpty()) {
      params.system(String.join(SYSTEM_SEPARATOR, system));
    }
    List<Tool> tools = tools(options instanceof ToolCallingChatOptions toolOptions ? toolOptions.getToolCallbacks() : null);
    for (Tool tool : tools) {
      params.addTool(tool);
    }
    if (!tools.isEmpty() && settings.forceFinishAfterToolCalls() > 0 && toolResponses >= settings.forceFinishAfterToolCalls()) {
      // 도구 목록은 그대로 둔다 — 이력의 tool_use 가 참조하고, thinking signature 가 도구 집합에 묶여 있다
      log.warn("Claude messages: 도구 결과 {}건 ≥ 상한 {} — tool_choice=none 으로 답을 강제한다", toolResponses, settings.forceFinishAfterToolCalls());
      params.toolChoice(ToolChoiceNone.builder().build());
    }
    OutputConfig outputConfig = outputConfig(options instanceof LlmChatOptions llmOptions ? llmOptions.getJsonSchema() : null);
    if (outputConfig != null) {
      params.outputConfig(outputConfig);
    }
    if (settings.autoCache()) {
      params.cacheControl(CacheControlEphemeral.builder().build());
    }
    return params.build();
  }

  /**
   * {@code output_config} — 구조화 출력 스키마(format)와 역할별 effort. 둘 다 없으면 null(미전송 = 서버 기본).
   * 스키마 이름은 Claude 형식에 자리가 없어 싣지 않는다.
   */
  private OutputConfig outputConfig(LlmJsonSchema jsonSchema) {
    String effort = StringUtils.trimToNull(settings.effort());
    if (jsonSchema == null && effort == null) {
      return null;
    }
    OutputConfig.Builder output = OutputConfig.builder();
    if (jsonSchema != null) {
      JsonOutputFormat.Schema schema = JsonOutputFormat.Schema.builder()
          .additionalProperties(jsonValues(AdvisorJson.toMap(jsonSchema.schema())))
          .build();
      output.format(JsonOutputFormat.builder().schema(schema).build());
    }
    if (effort != null) {
      output.effort(OutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT)));
    }
    return output.build();
  }

  /**
   * ToolCallback → Claude 도구 정의, <b>이름순</b>. 콜백 등록 순서가 바뀌어도 요청의 tools 가 같아야 프롬프트 캐시가 맞는다.
   */
  public static List<Tool> tools(List<ToolCallback> callbacks) {
    if (callbacks == null || callbacks.isEmpty()) {
      return List.of();
    }
    return callbacks.stream()
        .map(ToolCallback::getToolDefinition)
        .sorted(Comparator.comparing(ToolDefinition::name))
        .map(ClaudeMessagesChatModel::tool)
        .toList();
  }

  /**
   * 도구 정의 1건. 입력 스키마는 Spring AI 생성 원문에서 루트 {@code $schema} 만 뺀다. {@code type} 은 SDK 가 따로 가진 필드라 그쪽으로 넣는다
   * (additional property 로 넣으면 키가 두 번 직렬화된다).
   */
  private static Tool tool(ToolDefinition definition) {
    Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
    jsonObject(definition.inputSchema()).forEach((key, value) -> {
      if (TYPE_KEY.equals(key)) {
        schema.type(JsonValue.from(value));
      } else if (!SCHEMA_KEY.equals(key)) {
        schema.putAdditionalProperty(key, JsonValue.from(value));
      }
    });
    Tool.Builder tool = Tool.builder().name(definition.name()).inputSchema(schema.build());
    if (StringUtils.isNotBlank(definition.description())) {
      tool.description(definition.description());
    }
    return tool.build();
  }

  /**
   * 텍스트 → 블록. 공백뿐인 텍스트 블록은 API 가 400 으로 거부하므로 만들지 않는다.
   */
  private static List<ContentBlockParam> textBlocks(String text) {
    return StringUtils.isBlank(text) ? List.of() : List.of(ContentBlockParam.ofText(text));
  }

  /**
   * 원본이 없는 AssistantMessage(Slack 히스토리, 다른 공급자가 만든 라운드) → 텍스트 블록 + toolCalls 를 옮긴 tool_use 블록.
   */
  private static List<ContentBlockParam> assistantBlocks(AssistantMessage message) {
    List<ContentBlockParam> blocks = new ArrayList<>(textBlocks(message.getText()));
    for (AssistantMessage.ToolCall call : message.getToolCalls()) {
      ToolUseBlockParam.Input input = ToolUseBlockParam.Input.builder().additionalProperties(jsonValues(jsonObject(call.arguments()))).build();
      blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(call.id()).name(call.name()).input(input).build()));
    }
    return blocks;
  }

  /**
   * 도구 결과 → tool_result 블록들. 호출측이 이 블록들을 <b>한 user 메시지</b>에 담는다 — 병렬 도구 호출의 결과는 tool_use 바로 다음 메시지 하나에
   * 모두 있어야 한다(쪼개면 모델이 병렬 호출을 줄인다). 빈 결과는 content 없이 id 만 보낸다.
   */
  private static List<ContentBlockParam> toolResultBlocks(List<ToolResponseMessage.ToolResponse> responses) {
    List<ContentBlockParam> blocks = new ArrayList<>(responses.size());
    for (ToolResponseMessage.ToolResponse response : responses) {
      ToolResultBlockParam.Builder result = ToolResultBlockParam.builder().toolUseId(response.id());
      if (StringUtils.isNotBlank(response.responseData())) {
        result.content(response.responseData());
      }
      blocks.add(ContentBlockParam.ofToolResult(result.build()));
    }
    return blocks;
  }

  /**
   * JSON 객체 문자열 → Map. 비었거나 {@code null} 리터럴이면 빈 Map(인자 없는 도구 호출, 스키마 없는 도구).
   */
  private static Map<String, Object> jsonObject(String json) {
    Map<String, Object> map = AdvisorJson.readMap(json);
    return map == null ? Map.of() : map;
  }

  /**
   * Jackson 경계. 저장소 쪽 값(Jackson 3 으로 푼 Map·List·스칼라)을 SDK 의 {@link JsonValue}(Jackson 2)로 옮긴다. 키 순서는 유지된다.
   */
  private static Map<String, JsonValue> jsonValues(Map<String, Object> source) {
    Map<String, JsonValue> values = new LinkedHashMap<>();
    source.forEach((key, value) -> values.put(key, JsonValue.from(value)));
    return values;
  }

  /**
   * SDK 응답 → ChatResponse. SDK 는 필수 필드를 읽는 시점에 검증하므로 여기서 나는 해석 실패도 클라이언트의 분류 규칙(UNAVAILABLE)에 맞춘다 —
   * 그대로 흘리면 {@link LlmProviderException} 이 아니라서 폴백이 일어나지 않는다.
   */
  ChatResponse toChatResponse(Message message) {
    try {
      return mapResponse(message);
    } catch (AnthropicInvalidDataException e) {
      throw new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.UNAVAILABLE, 0, null, "응답 해석 실패: " + e.getMessage(), e);
    }
  }

  /**
   * 텍스트는 text 블록을 줄바꿈으로 결합, tool_use 는 toolCalls, 원본·공급자는 메타데이터. <b>stop_reason 이 tool_use 가 아니면 toolCalls 를 비운다</b> —
   * 어드바이저는 finishReason 이 아니라 {@code hasToolCalls()} 로 도구 실행을 정하므로, 잘렸거나(max_tokens) 거부된 응답의 tool_use 가 실행될 수 있다.
   */
  private ChatResponse mapResponse(Message message) {
    StringBuilder text = new StringBuilder();
    List<AssistantMessage.ToolCall> toolUses = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      block.text().ifPresent(textBlock -> appendLine(text, textBlock.text()));
      block.toolUse().ifPresent(toolUse -> toolUses.add(
          new AssistantMessage.ToolCall(toolUse.id(), TOOL_CALL_TYPE, toolUse.name(), toolInputJson(toolUse._input()))));
    }
    String stopReason = message.stopReason().map(StopReason::asString).orElse(UNKNOWN_STOP_REASON);
    String finishReason = finishReason(message.stopReason());
    boolean callsTools = LlmResponses.FINISH_TOOL_CALLS.equals(finishReason);
    boolean refused = LlmResponses.FINISH_REFUSAL.equals(finishReason);
    if (refused) {
      Optional<RefusalStopDetails> details = message.stopDetails();
      log.warn("Claude 응답 refusal: id={}, category={}, explanation={}", message.id(),
          details.flatMap(RefusalStopDetails::category).map(Object::toString).orElse(null),
          details.flatMap(RefusalStopDetails::explanation).orElse(null));
      if (text.isEmpty()) {
        // 거부는 content 가 빈 배열로 온다 — 사유 설명이 있으면 그것을 텍스트로
        text.append(details.flatMap(RefusalStopDetails::explanation).orElse(""));
      }
    } else if (!callsTools && !LlmResponses.FINISH_STOP.equals(finishReason)) {
      log.warn("Claude 응답 미완: stop_reason={}, id={}, 버린 tool_use={}", stopReason, message.id(), toolUses.size());
    }
    boolean droppedToolUse = !callsTools && !toolUses.isEmpty();
    List<AssistantMessage.ToolCall> toolCalls = callsTools ? toolUses : List.of();

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.CLAUDE.name());
    if (!droppedToolUse) {
      // tool_use 를 버린 응답은 원본을 싣지 않는다 — 되돌려 보내면 결과 없는 tool_use 가 이력에 남아 400 이다
      properties.put(MESSAGE_METADATA_KEY, message);
    }
    AssistantMessage assistant = AssistantMessage.builder()
        .content(text.toString())
        .properties(properties)
        .toolCalls(toolCalls)
        .media(List.of())
        .build();
    Generation generation = new Generation(assistant, ChatGenerationMetadata.builder().finishReason(finishReason).build());

    ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
        .id(message.id())
        .model(message.model().asString())
        .usage(usage(message.usage()))
        .keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.CLAUDE.name());
    if (LlmResponses.FINISH_INCOMPLETE.equals(finishReason)) {
      metadata.keyValue(LlmResponses.INCOMPLETE_REASON_METADATA_KEY, stopReason);
    }
    return new ChatResponse(List.of(generation), metadata.build());
  }

  /**
   * stop_reason → 중립 종료 사유. 모르는 값·없는 값(pause_turn, model_context_window_exceeded, SDK 가 모르는 새 값)은 INCOMPLETE 다.
   */
  private static String finishReason(Optional<StopReason> stopReason) {
    return stopReason.map(reason -> switch (reason.value()) {
      case END_TURN, STOP_SEQUENCE -> LlmResponses.FINISH_STOP;
      case TOOL_USE -> LlmResponses.FINISH_TOOL_CALLS;
      case MAX_TOKENS -> LlmResponses.FINISH_LENGTH;
      case REFUSAL -> LlmResponses.FINISH_REFUSAL;
      default -> LlmResponses.FINISH_INCOMPLETE;
    }).orElse(LlmResponses.FINISH_INCOMPLETE);
  }

  /**
   * Claude usage → Spring Usage. Claude 의 input_tokens 는 캐시를 뺀 수라 prompt 는 셋(input + cache_creation + cache_read)의 합이다.
   * 캐시 토큰은 Spring AI 가 라운드 합산해 주는 표준 슬롯(cacheRead·cacheWrite)으로도 싣는다 — native usage 는 합산 시 버려진다.
   */
  private static DefaultUsage usage(Usage usage) {
    long cacheRead = usage.cacheReadInputTokens().orElse(0L);
    long cacheWrite = usage.cacheCreationInputTokens().orElse(0L);
    int prompt = toInt(usage.inputTokens() + cacheWrite + cacheRead);
    int completion = toInt(usage.outputTokens());
    int thinking = toInt(usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L));
    return new DefaultUsage(prompt, completion, prompt + completion, new LlmTokenUsage(thinking, toInt(cacheRead)), cacheRead, cacheWrite);
  }

  /**
   * 토큰 수(long)를 Spring AI usage 의 int 로 옮긴다 — 음수는 0, int 범위를 넘으면 최댓값으로 묶는다.
   */
  private static int toInt(long tokens) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, tokens));
  }

  /**
   * tool_use 입력({@link JsonValue}) → JSON 문자열. SDK 값을 평범한 Map·List 로 푼 뒤 Jackson 3 으로 쓴다(Jackson 경계). 입력이 없으면 빈 객체.
   */
  private static String toolInputJson(JsonValue input) {
    Object plain = input.convert(Object.class);
    return plain == null ? EMPTY_OBJECT : TOOL_INPUT_MAPPER.writeValueAsString(plain);
  }

  /**
   * 줄 하나를 덧붙인다. 이미 내용이 있으면 줄바꿈으로 잇는다.
   */
  private static void appendLine(StringBuilder target, String line) {
    if (!target.isEmpty()) {
      target.append('\n');
    }
    target.append(line);
  }

  /**
   * 요청 메시지 누적기 — 합성 블록은 같은 role 이 이어지는 동안 한 메시지로 모으고, 원본 재전송은 그 자체로 한 메시지다.
   * Slack 스레드는 사람 메시지가 연달아 오거나 봇 메시지로 시작할 수 있는데 Messages API 의 첫 메시지는 user 여야 한다.
   */
  private static final class Conversation {

    private final List<MessageParam> messages = new ArrayList<>();
    private final List<ContentBlockParam> pending = new ArrayList<>();
    private boolean pendingAssistant;

    void user(List<ContentBlockParam> blocks) {
      append(false, blocks);
    }

    void assistant(List<ContentBlockParam> blocks) {
      append(true, blocks);
    }

    /**
     * 이 모델이 만든 응답을 그대로 되돌려 보낸다. {@code MessageCreateParams.Builder.addMessage(Message)} 가 하는 일과 같다({@code toParam()}).
     * thinking 블록·signature 가 바뀌면 안 되므로 앞뒤 메시지와 합치지 않는다.
     */
    void replay(Message original) {
      flush();
      messages.add(original.toParam());
    }

    /**
     * 누적을 마친다. 첫 메시지가 assistant 면 고정 user 메시지를 앞에 넣는다.
     */
    List<MessageParam> build() {
      flush();
      if (!messages.isEmpty() && messages.getFirst().isAssistant()) {
        messages.addFirst(MessageParam.ofUserOfContentBlockParams(List.of(ContentBlockParam.ofText(LEADING_USER_TEXT))));
      }
      return messages;
    }

    /**
     * 합성 블록을 누적한다. role 이 바뀌면 지금까지 모은 것을 메시지 하나로 내보낸 뒤 새로 모은다(같은 role 이 이어지면 한 메시지).
     */
    private void append(boolean assistant, List<ContentBlockParam> blocks) {
      if (blocks.isEmpty()) {
        return;
      }
      if (pendingAssistant != assistant) {
        flush();
      }
      pendingAssistant = assistant;
      pending.addAll(blocks);
    }

    /**
     * 모아 둔 블록을 메시지 하나로 내보내고 비운다. 모은 것이 없으면 아무 일도 하지 않는다.
     */
    private void flush() {
      if (pending.isEmpty()) {
        return;
      }
      List<ContentBlockParam> blocks = List.copyOf(pending);
      messages.add(pendingAssistant ? MessageParam.ofAssistantOfContentBlockParams(blocks) : MessageParam.ofUserOfContentBlockParams(blocks));
      pending.clear();
    }
  }
}
