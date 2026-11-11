package kr.hvy.blog.modules.advisor.client.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import kr.hvy.blog.modules.advisor.client.llm.LlmChatOptions;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmJsonSchema;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.llm.LlmTokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;

/**
 * Prompt ⇄ Messages API 변환 계약. HTTP·재시도·오류 분류는 목이다(클라이언트 테스트가 따로 있다).
 * 요청은 SDK 가 전송할 때와 같은 직렬화({@code params._body()})로 본문 JSON 을 만들어 <b>실제로 나가는 키</b>를 본다 — 접근자 단언은 "보내지 않는다" 를 증명하지 못한다.
 */
@DisplayName("ClaudeMessagesChatModel - Prompt ⇄ Messages 변환")
class ClaudeMessagesChatModelTest {

  private static final String MODEL = "claude-test-model";
  private static final int MAX_TOKENS = 8000;

  private static final String TEXT_BODY = """
      {"id":"msg_text","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[
         {"type":"thinking","thinking":"","signature":"sig-text"},
         {"type":"text","text":"첫 줄"},
         {"type":"text","text":"둘째 줄"}],
       "stop_reason":"end_turn","stop_sequence":null,
       "usage":{"input_tokens":100,"output_tokens":30,"cache_creation_input_tokens":20,"cache_read_input_tokens":50,
                "output_tokens_details":{"thinking_tokens":12}}}
      """;
  private static final String TOOL_USE_BODY = """
      {"id":"msg_tool","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[
         {"type":"thinking","thinking":"","signature":"EqQBCkYIBBgCKkD+sig/1=="},
         {"type":"redacted_thinking","data":"cmVkYWN0ZWQ="},
         {"type":"text","text":"조회해 보겠습니다."},
         {"type":"tool_use","id":"toolu_01","name":"echo","input":{"value":"a","times":null}}],
       "stop_reason":"tool_use","stop_sequence":null,
       "usage":{"input_tokens":100,"output_tokens":30}}
      """;
  private static final String REFUSAL_BODY = """
      {"id":"msg_refusal","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[],
       "stop_reason":"refusal","stop_sequence":null,
       "stop_details":{"type":"refusal","category":"cyber","explanation":"요청을 처리할 수 없습니다."},
       "usage":{"input_tokens":81,"output_tokens":0}}
      """;
  /** 필수 필드 usage 가 빠진 응답 — SDK 는 역직렬화가 아니라 접근 시점에 검증한다 */
  private static final String NO_USAGE_BODY = """
      {"id":"msg_broken","type":"message","role":"assistant","model":"claude-test-2026",
       "content":[{"type":"text","text":"본문"}],
       "stop_reason":"end_turn","stop_sequence":null}
      """;
  private static final String SCHEMA_JSON ="{\"type\":\"object\",\"properties\":{"
      + "\"picks\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"005930\"]}},"
      + "\"action\":{\"type\":[\"string\",\"null\"],\"enum\":[\"BUY\",\"HOLD\",null]}},"
      + "\"required\":[\"picks\",\"action\"],\"additionalProperties\":false}";

  /** ToolContext 를 마지막 인자로 받는 운영 툴킷과 같은 모양. 선언 순서를 일부러 이름순과 다르게 둔다 */
  static class FakeTools {

    final List<String> invocations = new ArrayList<>();

    @Tool(name = "zeta", description = "마지막 도구")
    public String zeta(@ToolParam(description = "값") String value, ToolContext context) {
      return value;
    }

    @Tool(name = "echo", description = "값을 그대로 돌려준다")
    public Map<String, Object> echo(@ToolParam(description = "값") String value,
        @ToolParam(required = false, description = "반복 수") Integer times, ToolContext context) {
      invocations.add(value + "/" + times + "/" + context.getContext().get("k"));
      return Map.of("value", value, "times", times == null ? 1 : times);
    }

    @Tool(name = "alpha", description = "첫 도구")
    public String alpha() {
      return "a";
    }
  }

  private ClaudeMessagesClient client;

  @BeforeEach
  void setUp() {
    client = mock(ClaudeMessagesClient.class);
  }

  private ClaudeMessagesChatModel model(String effort, boolean autoCache, int forceFinishAfterToolCalls) {
    return new ClaudeMessagesChatModel(client, new ClaudeModelSettings(MODEL, MAX_TOKENS, effort, autoCache, forceFinishAfterToolCalls));
  }

  private ClaudeMessagesChatModel model() {
    return model(null, false, 0);
  }

  /** 실제 API 응답 모양의 JSON 을 SDK 매퍼로 역직렬화한다 */
  private static com.anthropic.models.messages.Message message(String json) {
    try {
      return ObjectMappers.jsonMapper().readValue(json, com.anthropic.models.messages.Message.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** SDK 가 전송할 때와 같은 직렬화로 만든 요청 본문(Jackson 3 트리) */
  private static JsonNode body(MessageCreateParams params) {
    try {
      return AdvisorJson.MAPPER.readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode captureBody() {
    ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(client).create(captor.capture());
    return body(captor.getValue());
  }

  /** 이름 역순 — 등록 순서와 무관하게 이름순으로 나가는지 보려는 입력 */
  private static List<ToolCallback> toolsInReverseNameOrder() {
    return Arrays.stream(ToolCallbacks.from(new FakeTools()))
        .sorted(Comparator.comparing((ToolCallback callback) -> callback.getToolDefinition().name()).reversed())
        .toList();
  }

  private static LlmChatOptions toolOptions() {
    return LlmChatOptions.builder().toolCallbacks(toolsInReverseNameOrder()).build();
  }

  private static void assertTextMessage(JsonNode message, String role, String... texts) {
    assertThat(message.path("role").asString()).isEqualTo(role);
    JsonNode content = message.path("content");
    assertThat(content.size()).isEqualTo(texts.length);
    for (int i = 0; i < texts.length; i++) {
      assertThat(content.path(i).path("type").asString()).isEqualTo("text");
      assertThat(content.path(i).path("text").asString()).isEqualTo(texts[i]);
    }
  }

  @Nested
  @DisplayName("Prompt → 요청")
  class ToParams {

    @Test
    @DisplayName("model·max_tokens 는 설정값, 시스템 메시지는 전부 이어 system, 히스토리는 role 메시지. 도구·스키마·effort·캐시가 없으면 그 키들은 나가지 않는다")
    void 기본매핑() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      List<Message> messages = List.of(new SystemMessage("시스템 A"), new UserMessage("이전 질문"), new AssistantMessage("이전 답"),
          new SystemMessage("시스템 B"), new UserMessage("보여?"));

      model().call(new Prompt(messages));

      JsonNode body = captureBody();
      assertThat(body.path("model").asString()).isEqualTo(MODEL);
      assertThat(body.path("max_tokens").asInt()).isEqualTo(MAX_TOKENS);
      assertThat(body.path("system").asString()).isEqualTo("시스템 A\n\n시스템 B");
      assertThat(body.path("messages").size()).isEqualTo(3);
      assertTextMessage(body.path("messages").path(0), "user", "이전 질문");
      assertTextMessage(body.path("messages").path(1), "assistant", "이전 답");
      assertTextMessage(body.path("messages").path(2), "user", "보여?");
      assertThat(body.has("tools")).isFalse();
      assertThat(body.has("tool_choice")).isFalse();
      assertThat(body.has("output_config")).isFalse();
      assertThat(body.has("cache_control")).isFalse();
    }

    @Test
    @DisplayName("프롬프트 옵션의 model·maxTokens 는 무시하고(다른 공급자의 모델 ID 가 새지 않게) temperature·top_p·thinking 은 어떤 경우에도 보내지 않는다")
    void 옵션의_모델과_샘플링_무시() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      LlmChatOptions options = LlmChatOptions.builder().model("gpt-other-provider").maxTokens(123).temperature(0.3).topP(0.9).topK(5).build();

      model("high", true, 0).call(new Prompt(List.of(new UserMessage("q")), options));

      JsonNode body = captureBody();
      assertThat(body.path("model").asString()).isEqualTo(MODEL);
      assertThat(body.path("max_tokens").asInt()).isEqualTo(MAX_TOKENS);
      assertThat(body.has("temperature")).isFalse();
      assertThat(body.has("top_p")).isFalse();
      assertThat(body.has("top_k")).isFalse();
      assertThat(body.has("thinking")).as("thinking 은 서버가 항상 켠다 — 보내면 400").isFalse();
      assertThat(body.toString()).doesNotContain("gpt-other-provider");
    }

    @Test
    @DisplayName("연속된 같은 role 의 메시지는 한 메시지의 블록들로 합치고, 빈 텍스트는 블록을 만들지 않는다")
    void 연속_role_병합() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      List<Message> messages = List.of(new UserMessage("A"), new UserMessage("B"), new AssistantMessage("X"), new AssistantMessage(""),
          new AssistantMessage("Y"), new UserMessage("C"));

      model().call(new Prompt(messages));

      JsonNode turns = captureBody().path("messages");
      assertThat(turns.size()).isEqualTo(3);
      assertTextMessage(turns.path(0), "user", "A", "B");
      assertTextMessage(turns.path(1), "assistant", "X", "Y");
      assertTextMessage(turns.path(2), "user", "C");
    }

    @Test
    @DisplayName("첫 메시지가 assistant 면(Slack 스레드 루트가 봇 메시지) 고정 user 메시지를 앞에 넣는다")
    void 선두_assistant_보정() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));

      model().call(new Prompt(List.of(new SystemMessage("시스템"), new AssistantMessage("봇이 올린 루트 메시지"), new UserMessage("질문"))));

      JsonNode turns = captureBody().path("messages");
      assertThat(turns.size()).isEqualTo(3);
      assertTextMessage(turns.path(0), "user", ClaudeMessagesChatModel.LEADING_USER_TEXT);
      assertTextMessage(turns.path(1), "assistant", "봇이 올린 루트 메시지");
      assertTextMessage(turns.path(2), "user", "질문");
    }

    @Test
    @DisplayName("도구 정의는 등록 순서와 무관하게 이름순이고 이름·설명·입력 스키마($schema 제거, ToolContext 제외)를 싣는다. 상한 전에는 tool_choice 를 보내지 않는다")
    void 도구정의_이름순() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));

      model(null, false, 2).call(new Prompt(List.of(new UserMessage("q")), toolOptions()));

      JsonNode body = captureBody();
      JsonNode tools = body.path("tools");
      assertThat(tools.size()).isEqualTo(3);
      assertThat(List.of(tools.path(0).path("name").asString(), tools.path(1).path("name").asString(), tools.path(2).path("name").asString()))
          .containsExactly("alpha", "echo", "zeta");
      assertThat(body.has("tool_choice")).isFalse();
      JsonNode echo = tools.path(1);
      assertThat(echo.path("description").asString()).isEqualTo("값을 그대로 돌려준다");
      JsonNode schema = echo.path("input_schema");
      assertThat(schema.path("$schema").isMissingNode()).isTrue();
      assertThat(schema.path("type").asString()).isEqualTo("object");
      assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
      assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
      assertThat(schema.path("properties").path("value").path("type").asString()).isEqualTo("string");
      assertThat(schema.path("properties").path("times").path("type").asString()).isEqualTo("integer");
      assertThat(schema.path("properties").path("context").isMissingNode()).as("ToolContext 는 스키마에 없다").isTrue();
      assertThat(schema.path("required").toString()).isEqualTo("[\"value\"]");
    }

    @Test
    @DisplayName("원본 없는 AssistantMessage 의 도구 호출은 tool_use 블록으로 합성하고, 도구 결과들은 한 user 메시지의 tool_result 블록들로 묶는다")
    void 도구호출_합성과_결과_묶음() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      AssistantMessage assistant = AssistantMessage.builder()
          .content("")
          .toolCalls(List.of(
              new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"value\":\"a\",\"times\":2}"),
              new AssistantMessage.ToolCall("call_2", "function", "alpha", "")))
          .build();
      ToolResponseMessage results = ToolResponseMessage.builder()
          .responses(List.of(
              new ToolResponseMessage.ToolResponse("call_1", "echo", "{\"value\":\"a\"}"),
              new ToolResponseMessage.ToolResponse("call_2", "alpha", "")))
          .build();

      model().call(new Prompt(List.of(new UserMessage("q"), assistant, results, new UserMessage("이어서")), toolOptions()));

      JsonNode turns = captureBody().path("messages");
      assertThat(turns.size()).isEqualTo(3);
      JsonNode toolUses = turns.path(1).path("content");
      assertThat(turns.path(1).path("role").asString()).isEqualTo("assistant");
      assertThat(toolUses.size()).as("빈 텍스트는 블록이 되지 않는다").isEqualTo(2);
      assertThat(toolUses.path(0).path("type").asString()).isEqualTo("tool_use");
      assertThat(toolUses.path(0).path("id").asString()).isEqualTo("call_1");
      assertThat(toolUses.path(0).path("name").asString()).isEqualTo("echo");
      assertThat(toolUses.path(0).path("input").toString()).isEqualTo("{\"value\":\"a\",\"times\":2}");
      assertThat(toolUses.path(1).path("id").asString()).isEqualTo("call_2");
      assertThat(toolUses.path(1).path("input").toString()).as("인자가 비면 빈 객체").isEqualTo("{}");
      JsonNode toolResults = turns.path(2).path("content");
      assertThat(turns.path(2).path("role").asString()).isEqualTo("user");
      assertThat(toolResults.size()).as("결과 2건 + 이어진 사용자 텍스트가 한 메시지").isEqualTo(3);
      assertThat(toolResults.path(0).path("type").asString()).isEqualTo("tool_result");
      assertThat(toolResults.path(0).path("tool_use_id").asString()).isEqualTo("call_1");
      assertThat(toolResults.path(0).path("content").asString()).isEqualTo("{\"value\":\"a\"}");
      assertThat(toolResults.path(1).path("tool_use_id").asString()).isEqualTo("call_2");
      assertThat(toolResults.path(1).has("content")).as("빈 결과는 content 를 보내지 않는다").isFalse();
      assertThat(toolResults.path(2).path("text").asString()).isEqualTo("이어서");
    }

    @Test
    @DisplayName("구조화 출력 스키마는 output_config.format(json_schema)으로 원문 그대로, effort 는 소문자로 output_config.effort 에 싣는다")
    void 구조화출력과_effort() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      LlmJsonSchema schema = new LlmJsonSchema("AdviceResponse", AdvisorJson.MAPPER.readTree(SCHEMA_JSON));

      model(" High ", false, 0).call(new Prompt(List.of(new SystemMessage("s"), new UserMessage("{}")), LlmChatOptions.builder().jsonSchema(schema).build()));

      JsonNode output = captureBody().path("output_config");
      assertThat(output.path("effort").asString()).isEqualTo("high");
      assertThat(output.path("format").path("type").asString()).isEqualTo("json_schema");
      assertThat(output.path("format").path("schema")).isEqualTo(schema.schema());
      assertThat(output.path("format").has("name")).as("Claude 형식에는 스키마 이름이 없다").isFalse();
    }

    @Test
    @DisplayName("effort 가 공백이면 effort 를, 스키마가 없으면 format 을 보내지 않고 둘 다 없으면 output_config 자체가 없다")
    void output_config_선택() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      LlmJsonSchema schema = new LlmJsonSchema("AdviceResponse", AdvisorJson.MAPPER.readTree(SCHEMA_JSON));

      model("  ", false, 0).call(new Prompt(List.of(new UserMessage("q")), LlmChatOptions.builder().jsonSchema(schema).build()));
      model("medium", false, 0).call(new Prompt(List.of(new UserMessage("q"))));
      model("  ", false, 0).call(new Prompt(List.of(new UserMessage("q"))));

      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(client, times(3)).create(captor.capture());
      JsonNode schemaOnly = body(captor.getAllValues().get(0)).path("output_config");
      assertThat(schemaOnly.has("format")).isTrue();
      assertThat(schemaOnly.has("effort")).isFalse();
      JsonNode effortOnly = body(captor.getAllValues().get(1)).path("output_config");
      assertThat(effortOnly.path("effort").asString()).isEqualTo("medium");
      assertThat(effortOnly.has("format")).isFalse();
      assertThat(body(captor.getAllValues().get(2)).has("output_config")).isFalse();
    }

    @Test
    @DisplayName("autoCache 면 top-level cache_control(ephemeral) 을 보낸다")
    void 자동캐싱() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));

      model(null, true, 0).call(new Prompt(List.of(new UserMessage("q"))));

      assertThat(captureBody().path("cache_control").toString()).isEqualTo("{\"type\":\"ephemeral\"}");
    }

    @Test
    @DisplayName("도구 결과가 forceFinishAfterToolCalls 이상 쌓이면 tool_choice=none 으로 답을 강제하되 도구 목록은 유지한다. 상한 미만·도구 없음이면 보내지 않는다")
    void 강제마무리() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      ToolResponseMessage one = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("c1", "echo", "{}"))).build();
      ToolResponseMessage two = ToolResponseMessage.builder().responses(List.of(
          new ToolResponseMessage.ToolResponse("c1", "echo", "{}"), new ToolResponseMessage.ToolResponse("c2", "echo", "{}"))).build();
      ClaudeMessagesChatModel model = model(null, false, 2);

      model.call(new Prompt(List.of(new UserMessage("q"), two), toolOptions()));
      model.call(new Prompt(List.of(new UserMessage("q"), one), toolOptions()));
      model.call(new Prompt(List.of(new UserMessage("q"), two)));

      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(client, times(3)).create(captor.capture());
      JsonNode forced = body(captor.getAllValues().get(0));
      assertThat(forced.path("tool_choice").toString()).isEqualTo("{\"type\":\"none\"}");
      assertThat(forced.path("tools").size()).as("도구 목록은 유지(이력의 tool_use 참조·thinking signature 의 도구 집합 바인딩)").isEqualTo(3);
      assertThat(body(captor.getAllValues().get(1)).has("tool_choice")).as("상한 미만").isFalse();
      assertThat(body(captor.getAllValues().get(2)).has("tool_choice")).as("도구 없음").isFalse();
    }

    @Test
    @DisplayName("옵션이 ToolCallingChatOptions 가 아니면 도구 없음, LlmChatOptions 가 아니면 스키마 없음으로 처리한다")
    void 옵션_타입별_처리() {
      when(client.create(any())).thenReturn(message(TEXT_BODY));
      ClaudeMessagesChatModel model = model();

      model.call(new Prompt(List.of(new UserMessage("q")), ChatOptions.builder().model("x").build()));
      model.call(new Prompt(List.of(new UserMessage("q")), ToolCallingChatOptions.builder().toolCallbacks(toolsInReverseNameOrder()).build()));

      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(client, times(2)).create(captor.capture());
      JsonNode plain = body(captor.getAllValues().get(0));
      assertThat(plain.has("tools")).isFalse();
      assertThat(plain.has("output_config")).isFalse();
      JsonNode toolCalling = body(captor.getAllValues().get(1));
      assertThat(toolCalling.path("tools").size()).isEqualTo(3);
      assertThat(toolCalling.has("output_config")).isFalse();
    }

    @Test
    @DisplayName("기본 옵션은 빈 LlmChatOptions 다 — model·maxTokens 를 실으면 폴백 래퍼를 거쳐 다른 공급자로 샌다")
    void 기본옵션은_비어있다() {
      LlmChatOptions options = model("high", true, 12).getOptions();

      assertThat(options).isExactlyInstanceOf(LlmChatOptions.class);
      assertThat(options.getModel()).isNull();
      assertThat(options.getMaxTokens()).isNull();
      assertThat(options.getJsonSchema()).isNull();
      assertThat(options.getToolCallbacks()).isNullOrEmpty();
      assertThat(options.getToolContext()).isNullOrEmpty();
    }

    @Test
    @DisplayName("모델 ID 가 비었으면 호출 없이 MISCONFIGURED(폴백 대상), 보낼 메시지가 없으면 REQUEST_INVALID 로 던진다")
    void 요청_전_검증() {
      ClaudeMessagesChatModel noModel = new ClaudeMessagesChatModel(client, new ClaudeModelSettings(" ", MAX_TOKENS, null, false, 0));

      assertThatThrownBy(() -> noModel.call(new Prompt(List.of(new UserMessage("q")))))
          .isInstanceOfSatisfying(LlmProviderException.class, e -> {
            assertThat(e.getProvider()).isEqualTo(LlmProvider.CLAUDE);
            assertThat(e.getKind()).isEqualTo(LlmFailureKind.MISCONFIGURED);
          });
      assertThatThrownBy(() -> model().call(new Prompt(List.of(new SystemMessage("시스템만")))))
          .isInstanceOfSatisfying(LlmProviderException.class, e -> assertThat(e.getKind()).isEqualTo(LlmFailureKind.REQUEST_INVALID));
      verifyNoInteractions(client);
    }
  }

  @Nested
  @DisplayName("2라운드 재전송")
  class Replay {

    @Test
    @DisplayName("1라운드 응답(thinking·signature·redacted_thinking·tool_use)은 다음 요청에 원본 그대로 실리고, 그 앞부분(system·tools·앞선 메시지)은 1라운드 요청과 같다")
    void 원본_그대로_재전송() {
      when(client.create(any())).thenReturn(message(TOOL_USE_BODY)).thenReturn(message(TEXT_BODY));
      ClaudeMessagesChatModel model = model("medium", true, 12);
      LlmChatOptions options = toolOptions();
      List<Message> firstTurn = List.of(new SystemMessage("시스템"), new AssistantMessage("봇 루트"), new UserMessage("q"));
      ChatResponse first = model.call(new Prompt(firstTurn, options));
      AssistantMessage assistant = first.getResult().getOutput();
      ToolResponseMessage toolResponse = ToolResponseMessage.builder()
          .responses(List.of(new ToolResponseMessage.ToolResponse("toolu_01", "echo", "{\"value\":\"a\",\"times\":1}")))
          .build();

      model.call(new Prompt(List.of(new SystemMessage("시스템"), new AssistantMessage("봇 루트"), new UserMessage("q"), assistant, toolResponse), options));

      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(client, times(2)).create(captor.capture());
      JsonNode round1 = body(captor.getAllValues().get(0));
      JsonNode round2 = body(captor.getAllValues().get(1));
      JsonNode turns = round2.path("messages");
      assertThat(turns.size()).isEqualTo(5);

      JsonNode replayed = turns.path(3);
      assertThat(replayed.path("role").asString()).isEqualTo("assistant");
      assertThat(replayed.path("content")).as("응답 content 와 블록 순서·내용이 같다").isEqualTo(AdvisorJson.MAPPER.readTree(TOOL_USE_BODY).path("content"));
      assertThat(replayed.path("content").path(0).path("signature").asString()).isEqualTo("EqQBCkYIBBgCKkD+sig/1==");
      assertThat(replayed.path("content").path(1).path("data").asString()).isEqualTo("cmVkYWN0ZWQ=");

      JsonNode toolResult = turns.path(4);
      assertThat(toolResult.path("role").asString()).isEqualTo("user");
      assertThat(toolResult.path("content").size()).isEqualTo(1);
      assertThat(toolResult.path("content").path(0).path("tool_use_id").asString()).isEqualTo("toolu_01");

      // thinking signature 는 system·도구 집합·앞선 메시지에 묶이고 프롬프트 캐시는 바이트 접두다 — 앞부분이 달라지면 2라운드가 400 이거나 캐시가 깨진다
      assertThat(round2.path("system").toString()).isEqualTo(round1.path("system").toString());
      assertThat(round2.path("tools").toString()).isEqualTo(round1.path("tools").toString());
      for (int i = 0; i < round1.path("messages").size(); i++) {
        assertThat(turns.path(i).toString()).as("messages[" + i + "]").isEqualTo(round1.path("messages").path(i).toString());
      }
    }
  }

  @Nested
  @DisplayName("ChatClient + ToolCallingAdvisor 도구 루프")
  class ToolLoop {

    /**
     * 운영 채팅 ChatClient 와 같은 조립(AdvisorAiConfig) — 이 모델이 Spring AI 의 도구 루프 안에서 OpenAI 모델과 같은 계약으로 도는지 본다. HTTP 만 목이다.
     */
    @Test
    @DisplayName("tool_use → @Tool 실행(ToolContext 전달) → 원본+결과 재전송 → 최종 답. usage 는 라운드 합산이고 캐시 토큰은 표준 슬롯으로 합쳐진다")
    void 도구루프_두라운드() {
      when(client.create(any())).thenReturn(message(TOOL_USE_BODY)).thenReturn(message(TEXT_BODY));
      ToolCallingManager manager = ToolCallingManager.builder()
          .maxCallsPerTool(6)
          .maxTotalToolCalls(12)
          .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
          .build();
      ChatClient chatClient = ChatClient.builder(model("medium", true, 12), ObservationRegistry.NOOP, null, null,
              ToolCallingAdvisor.builder().toolCallingManager(manager).conversationHistoryEnabled(true))
          .build();
      FakeTools toolkit = new FakeTools();

      ChatResponse response = chatClient.prompt()
          .system("시스템 프롬프트")
          .user("q")
          .tools(toolkit)
          .toolContext(Map.of("k", "v"))
          .call()
          .chatResponse();

      assertThat(response.getResult().getOutput().getText()).isEqualTo("첫 줄\n둘째 줄");
      assertThat(toolkit.invocations).as("모델이 준 입력(null 포함)과 ToolContext 로 도구가 불렸다").containsExactly("a/null/v");
      assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.CLAUDE);
      assertThat(LlmResponses.finishReason(response)).isEqualTo(LlmResponses.FINISH_STOP);

      ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
      verify(client, times(2)).create(captor.capture());
      JsonNode round1 = body(captor.getAllValues().get(0));
      JsonNode round2 = body(captor.getAllValues().get(1));
      assertThat(round1.path("system").asString()).isEqualTo("시스템 프롬프트");
      assertThat(round1.path("tools").size()).isEqualTo(3);
      assertThat(round1.path("messages").size()).isEqualTo(1);
      JsonNode turns = round2.path("messages");
      assertThat(turns.size()).isEqualTo(3);
      assertThat(turns.path(0).toString()).isEqualTo(round1.path("messages").path(0).toString());
      assertThat(turns.path(1).path("content")).as("어드바이저가 이력에 붙인 AssistantMessage 에서 원본을 꺼내 그대로 보낸다")
          .isEqualTo(AdvisorJson.MAPPER.readTree(TOOL_USE_BODY).path("content"));
      JsonNode toolResult = turns.path(2).path("content").path(0);
      assertThat(toolResult.path("type").asString()).isEqualTo("tool_result");
      assertThat(toolResult.path("tool_use_id").asString()).isEqualTo("toolu_01");
      assertThat(toolResult.path("content").asString()).contains("\"value\"").contains("\"times\"");
      assertThat(round2.path("tools").toString()).isEqualTo(round1.path("tools").toString());
      assertThat(round2.has("tool_choice")).isFalse();

      Usage usage = response.getMetadata().getUsage();
      assertThat(usage.getPromptTokens()).isEqualTo(270);
      assertThat(usage.getCompletionTokens()).isEqualTo(60);
      assertThat(usage.getCacheReadInputTokens()).as("Spring AI 가 합산해 주는 캐시 토큰").isEqualTo(50L);
    }
  }

  @Nested
  @DisplayName("응답 → ChatResponse")
  class ToChatResponse {

    @Test
    @DisplayName("text 블록은 줄바꿈으로 잇고 finishReason 은 STOP. 공급자는 응답·메시지 메타데이터 양쪽에, 원본 Message 는 메시지 메타데이터에 싣는다")
    void 텍스트응답() {
      com.anthropic.models.messages.Message original = message(TEXT_BODY);
      when(client.create(any())).thenReturn(original);

      ChatResponse response = model().call(new Prompt(List.of(new UserMessage("q"))));

      AssistantMessage output = response.getResult().getOutput();
      assertThat(output.getText()).isEqualTo("첫 줄\n둘째 줄");
      assertThat(response.hasToolCalls()).isFalse();
      assertThat(LlmResponses.finishReason(response)).isEqualTo(LlmResponses.FINISH_STOP);
      assertThat(LlmResponses.incompleteReason(response)).isNull();
      assertThat(LlmResponses.provider(response)).isEqualTo(LlmProvider.CLAUDE);
      assertThat(LlmResponses.provider(output)).isEqualTo(LlmProvider.CLAUDE);
      Object providerValue = response.getMetadata().get(LlmResponses.PROVIDER_METADATA_KEY);
      assertThat(providerValue).as("상수명 문자열로 싣는다").isEqualTo("CLAUDE");
      assertThat(output.getMetadata().get(ClaudeMessagesChatModel.MESSAGE_METADATA_KEY)).isSameAs(original);
      assertThat(response.getMetadata().getId()).isEqualTo("msg_text");
      assertThat(response.getMetadata().getModel()).isEqualTo("claude-test-2026");
    }

    @Test
    @DisplayName("prompt 토큰은 input + cache_creation + cache_read, native usage 는 thinking·cache_read, 캐시는 Spring 표준 슬롯에도 싣는다")
    void 사용량() {
      when(client.create(any())).thenReturn(message(TEXT_BODY)).thenReturn(message(TOOL_USE_BODY));

      Usage usage = model().call(new Prompt(List.of(new UserMessage("q")))).getMetadata().getUsage();
      Usage withoutDetails = model().call(new Prompt(List.of(new UserMessage("q")))).getMetadata().getUsage();

      assertThat(usage.getPromptTokens()).isEqualTo(170);
      assertThat(usage.getCompletionTokens()).isEqualTo(30);
      assertThat(usage.getTotalTokens()).isEqualTo(200);
      assertThat(usage.getCacheReadInputTokens()).isEqualTo(50L);
      assertThat(usage.getCacheWriteInputTokens()).isEqualTo(20L);
      assertThat(usage.getNativeUsage()).isEqualTo(new LlmTokenUsage(12, 50));
      assertThat(withoutDetails.getPromptTokens()).as("캐시·상세 필드가 없는 응답").isEqualTo(100);
      assertThat(withoutDetails.getCacheReadInputTokens()).isEqualTo(0L);
      assertThat(withoutDetails.getNativeUsage()).isEqualTo(new LlmTokenUsage(0, 0));
    }

    @Test
    @DisplayName("tool_use 블록은 toolCalls(id·function·name·입력 JSON 원문)이고 finishReason 은 TOOL_CALLS 다")
    void 도구호출응답() {
      when(client.create(any())).thenReturn(message(TOOL_USE_BODY));

      ChatResponse response = model().call(new Prompt(List.of(new UserMessage("q")), toolOptions()));

      AssistantMessage output = response.getResult().getOutput();
      assertThat(output.getText()).isEqualTo("조회해 보겠습니다.");
      assertThat(response.hasToolCalls()).isTrue();
      assertThat(output.getToolCalls()).containsExactly(new AssistantMessage.ToolCall("toolu_01", "function", "echo", "{\"value\":\"a\",\"times\":null}"));
      assertThat(LlmResponses.finishReason(response)).isEqualTo(LlmResponses.FINISH_TOOL_CALLS);
      assertThat(output.getMetadata()).containsKey(ClaudeMessagesChatModel.MESSAGE_METADATA_KEY);
    }

    @ParameterizedTest(name = "stop_reason={0} → {1}")
    @CsvSource({
        "end_turn, STOP, ",
        "stop_sequence, STOP, ",
        "tool_use, TOOL_CALLS, ",
        "max_tokens, LENGTH, ",
        "refusal, REFUSAL, ",
        "pause_turn, INCOMPLETE, pause_turn",
        "model_context_window_exceeded, INCOMPLETE, model_context_window_exceeded",
        "sdk_does_not_know_this_yet, INCOMPLETE, sdk_does_not_know_this_yet"})
    @DisplayName("stop_reason 은 중립 종료 사유로 옮기고, 모르는 값은 INCOMPLETE + 사유 원문이다")
    void 종료사유_매핑(String stopReason, String finishReason, String incompleteReason) {
      when(client.create(any())).thenReturn(message(TEXT_BODY.replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"" + stopReason + "\"")));

      ChatResponse response = model().call(new Prompt(List.of(new UserMessage("q"))));

      assertThat(LlmResponses.finishReason(response)).isEqualTo(finishReason);
      assertThat(LlmResponses.incompleteReason(response)).isEqualTo(incompleteReason);
    }

    @Test
    @DisplayName("stop_reason 이 없으면 INCOMPLETE 이고 사유는 unknown 이다")
    void 종료사유_없음() {
      when(client.create(any())).thenReturn(message(TEXT_BODY.replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":null")));

      ChatResponse response = model().call(new Prompt(List.of(new UserMessage("q"))));

      assertThat(LlmResponses.finishReason(response)).isEqualTo(LlmResponses.FINISH_INCOMPLETE);
      assertThat(LlmResponses.incompleteReason(response)).isEqualTo(ClaudeMessagesChatModel.UNKNOWN_STOP_REASON);
    }

    @Test
    @DisplayName("refusal 은 content 가 빈 배열이어도 REFUSAL 이고 텍스트는 stop_details 의 설명(없으면 빈 문자열)이다")
    void 거부응답() {
      when(client.create(any())).thenReturn(message(REFUSAL_BODY))
          .thenReturn(message(REFUSAL_BODY.replace("\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"요청을 처리할 수 없습니다.\"},", "")));

      ChatResponse withDetails = model().call(new Prompt(List.of(new UserMessage("q"))));
      ChatResponse withoutDetails = model().call(new Prompt(List.of(new UserMessage("q"))));

      assertThat(LlmResponses.finishReason(withDetails)).isEqualTo(LlmResponses.FINISH_REFUSAL);
      assertThat(withDetails.getResult().getOutput().getText()).isEqualTo("요청을 처리할 수 없습니다.");
      assertThat(withDetails.hasToolCalls()).isFalse();
      assertThat(withDetails.getMetadata().getUsage().getPromptTokens()).isEqualTo(81);
      assertThat(LlmResponses.finishReason(withoutDetails)).isEqualTo(LlmResponses.FINISH_REFUSAL);
      assertThat(withoutDetails.getResult().getOutput().getText()).isEmpty();
    }

    @ParameterizedTest(name = "stop_reason={0} → {1}")
    @CsvSource({"max_tokens, LENGTH", "refusal, REFUSAL", "model_context_window_exceeded, INCOMPLETE"})
    @DisplayName("stop_reason 이 tool_use 가 아니면 tool_use 블록이 있어도 toolCalls 를 비운다 — 잘리거나 거부된 도구 호출이 실행되면 안 된다. 그 응답은 원본도 싣지 않는다")
    void 도구호출_버림(String stopReason, String finishReason) {
      when(client.create(any())).thenReturn(message(TOOL_USE_BODY.replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"" + stopReason + "\"")));

      ChatResponse response = model().call(new Prompt(List.of(new UserMessage("q")), toolOptions()));

      AssistantMessage output = response.getResult().getOutput();
      assertThat(LlmResponses.finishReason(response)).isEqualTo(finishReason);
      assertThat(response.hasToolCalls()).isFalse();
      assertThat(output.getToolCalls()).isEmpty();
      assertThat(output.getText()).as("있는 텍스트는 남긴다").isEqualTo("조회해 보겠습니다.");
      assertThat(output.getMetadata()).as("되돌려 보내면 결과 없는 tool_use 가 남는다").doesNotContainKey(ClaudeMessagesChatModel.MESSAGE_METADATA_KEY);
      assertThat(LlmResponses.provider(output)).isEqualTo(LlmProvider.CLAUDE);
    }

    @Test
    @DisplayName("필수 필드가 빠진 응답은 UNAVAILABLE 로 던진다 — SDK 는 읽는 시점에 검증하므로 그대로 두면 폴백 대상에서 빠진다")
    void 응답_해석_실패() {
      when(client.create(any())).thenReturn(message(NO_USAGE_BODY));

      assertThatThrownBy(() -> model().call(new Prompt(List.of(new UserMessage("q")))))
          .isInstanceOfSatisfying(LlmProviderException.class, e -> {
            assertThat(e.getProvider()).isEqualTo(LlmProvider.CLAUDE);
            assertThat(e.getKind()).isEqualTo(LlmFailureKind.UNAVAILABLE);
            assertThat(e.getStatus()).isZero();
            assertThat(e.getKind().isFallbackable()).isTrue();
          })
          .hasCauseInstanceOf(AnthropicInvalidDataException.class);
    }
  }
}
