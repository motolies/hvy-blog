package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmResponses;
import kr.hvy.blog.modules.advisor.client.llm.LlmTokenUsage;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import kr.hvy.blog.modules.advisor.client.openai.dto.InputMessage;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesRequest;
import kr.hvy.blog.modules.advisor.client.openai.dto.ResponsesResponse;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 가짜 HTTP 클라이언트 위의 <b>진짜</b> Responses 모델 + ChatClient 로 호출 규약을 고정한다: 시스템·사용자 메시지, strict text.format, 모델 기본 옵션 보존,
 * 응답 파싱·usage, finishReason(LENGTH/INCOMPLETE/REFUSAL) 선검사. 이전의 람다 ChatModel 스텁은 옵션 타입을 몰라 "assist 가 judge 모델로 나가는" 버그를 못 잡았다.
 * <p>
 * 공급자 중립 계약(2026-11-11): 클라이언트는 중립 옵션(LlmChatOptions.jsonSchema)만 넘기고, 응답의 공급자 표시·폴백 사유를 결과와 options 맵에 싣는다 —
 * 폴백 래퍼가 싣는 표시는 메타데이터만 지정한 스텁 응답으로 흉내 낸다.
 */
class MarketJudgeClientTest {

  static final String FIXTURE = """
      {"regime":{"code":"RISK_ON","kospiDir":"UP","kosdaqDir":"NEUTRAL","pUp":"0.70","rationale":"근거"},
       "sectors":[{"code":"G2510","reason":"반도체"}],
       "picks":[{"ticker":"005930","direction":"LONG","conviction":"0.80","thesis":"t","risk":"r","citedFeatures":[{"name":"r20","value":0.081}]}],
       "summary":"요약"}
      """;
  static final String SCHEMA = AdviceSchemaFactory.schemaJson(List.of("005930"), List.of("G2510"));

  private OpenAiResponsesClient http;
  private MarketJudgeClient client;

  @BeforeEach
  void setUp() {
    http = mock(OpenAiResponsesClient.class);
    // AdvisorAiConfig.assistResponsesChatModel + assistChatClient 와 같은 조립 — 모델 기본 옵션이 assist 값
    OpenAiResponsesChatModel model = new OpenAiResponsesChatModel(http, ResponsesChatOptions.builder().model("assist-model").maxTokens(1234).build(), 0);
    client = new MarketJudgeClient(ChatClient.builder(model).build(), "hint");
  }

  /** message 1개짜리 Responses 응답. contentType 이 refusal 이면 refusal 파트, 아니면 output_text */
  static ResponsesResponse response(String status, String incompleteReason, String contentType, String content) {
    String incomplete = incompleteReason == null ? "" : ",\"incomplete_details\":{\"reason\":\"" + incompleteReason + "\"}";
    String field = ResponsesResponse.CONTENT_REFUSAL.equals(contentType) ? "refusal" : "text";
    String json = "{\"id\":\"resp_1\",\"status\":\"" + status + "\"" + incomplete + ",\"model\":\"assist-model-2026\","
        + "\"output\":[{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"status\":\"completed\","
        + "\"content\":[{\"type\":\"" + contentType + "\",\"" + field + "\":" + AdvisorJson.MAPPER.writeValueAsString(content) + "}]}],"
        + "\"usage\":{\"input_tokens\":6000,\"output_tokens\":1900,\"total_tokens\":7900,"
        + "\"input_tokens_details\":{\"cached_tokens\":100},\"output_tokens_details\":{\"reasoning_tokens\":300}}}";
    return AdvisorJson.read(json, ResponsesResponse.class);
  }

  static ResponsesResponse completed(String text) {
    return response("completed", null, "output_text", text);
  }

  @Test
  @DisplayName("요청: 시스템→instructions·사용자 JSON→input, 모델 기본 옵션(assist model·max_output_tokens)이 살고 text.format 은 strict 스키마(이름=응답 클래스명)")
  void 요청규약_모델기본옵션_보존() {
    when(http.create(any())).thenReturn(completed(FIXTURE));
    PromptPayload payload = new PromptPayload("{\"asOf\":\"2026-09-11\"}", List.of("005930"), List.of("G2510"), 1, false, 10);

    client.judge("SYSTEM PROMPT", payload, SCHEMA);

    ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(http).create(captor.capture());
    ResponsesRequest request = captor.getValue();
    assertThat(request.instructions()).isEqualTo("SYSTEM PROMPT");
    assertThat(request.input()).containsExactly(new InputMessage("user", "{\"asOf\":\"2026-09-11\"}"));
    assertThat(request.model()).as("중립 .options(jsonSchema) 가 모델 기본 옵션을 버리면 안 된다(assist→judge 오배선 회귀)").isEqualTo("assist-model");
    assertThat(request.maxOutputTokens()).isEqualTo(1234);
    assertThat(request.text().format().type()).isEqualTo("json_schema");
    assertThat(request.text().format().name()).isEqualTo("AdviceResponse");
    assertThat(request.text().format().strict()).isTrue();
    assertThat(request.text().format().schema()).isEqualTo(AdvisorJson.MAPPER.readTree(SCHEMA));
    assertThat(request.tools()).isNull();
    assertThat(request.toolChoice()).isNull();
    assertThat(request.include()).as("도구 없는 단발 호출은 reasoning 블롭을 받지 않는다").isNull();
    assertThat(request.store()).isFalse();
  }

  @Test
  @DisplayName("응답 JSON 이 레코드로 파싱되고 usage(입력·출력·추론·캐시)·model·responseId·rawText 가 기록된다")
  void 응답파싱_usage() {
    when(http.create(any())).thenReturn(completed(FIXTURE));

    MarketJudgeClient.JudgeResult result = client.judge("s", payload(), SCHEMA);

    assertThat(result.response().picks()).hasSize(1);
    assertThat(result.response().picks().getFirst().ticker()).isEqualTo("005930");
    assertThat(result.response().regime().pUp()).isEqualTo("0.70");
    assertThat(result.usage().getPromptTokens()).isEqualTo(6000);
    assertThat(result.usage().getCompletionTokens()).isEqualTo(1900);
    assertThat(result.reasoningTokens()).as("단발 호출은 ToolCallingAdvisor 합산을 거쳐도 native LlmTokenUsage 가 보존된다").isEqualTo(300);
    assertThat(result.cachedTokens()).isEqualTo(100);
    assertThat(result.model()).isEqualTo("assist-model-2026");
    assertThat(result.responseId()).isEqualTo("resp_1");
    assertThat(result.rawText()).isEqualTo(FIXTURE);
    assertThat(result.options()).containsEntry("responseFormat", "json_schema/strict").containsEntry("schemaName", "AdviceResponse");
    assertThat(result.provider()).as("Responses 모델이 실은 공급자 표시").isEqualTo(LlmProvider.OPENAI);
    assertThat(result.options()).containsEntry("provider", "OPENAI").doesNotContainKey("fallbackReason");
  }

  /** 공급자 모델 없이 응답 메타데이터·finishReason 만 지정하는 스텁 클라이언트 — 폴백 래퍼·다른 공급자 모델이 싣는 표시를 흉내 낸다 */
  private static MarketJudgeClient stubClient(ChatResponseMetadata metadata, String finishReason, String text) {
    ChatModel stub = prompt -> new ChatResponse(
        List.of(new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(finishReason).build())), metadata);
    return new MarketJudgeClient(ChatClient.create(stub), "hint");
  }

  @Test
  @DisplayName("폴백 응답: 공급자 표시와 폴백 사유가 CallResult·JudgeResult 와 options 맵(→ options_json)에 실린다")
  void 공급자_폴백사유_기록() {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder().id("resp_f").model("gpt-fallback").usage(new DefaultUsage(10, 5))
        .keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.OPENAI.name())
        .keyValue(LlmResponses.FALLBACK_REASON_METADATA_KEY, "EXHAUSTED: credit balance too low").build();
    MarketJudgeClient fallback = stubClient(metadata, LlmResponses.FINISH_STOP, FIXTURE);

    MarketJudgeClient.CallResult<kr.hvy.blog.modules.advisor.client.llm.AdviceResponse> call =
        fallback.call("s", "{}", SCHEMA, kr.hvy.blog.modules.advisor.client.llm.AdviceResponse.class);
    MarketJudgeClient.JudgeResult judged = fallback.judge("s", payload(), SCHEMA);

    assertThat(call.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(judged.provider()).isEqualTo(LlmProvider.OPENAI);
    assertThat(call.options()).containsEntry("provider", "OPENAI").containsEntry("fallbackReason", "EXHAUSTED: credit balance too low")
        .containsEntry("model", "gpt-fallback");
    assertThat(AdvisorJson.write(judged.options())).as("저장되는 options_json")
        .contains("\"provider\":\"OPENAI\"").contains("\"fallbackReason\":\"EXHAUSTED: credit balance too low\"");
  }

  @Test
  @DisplayName("1순위가 답한 응답은 provider 만 실리고 fallbackReason 키는 없다. 공급자 표시가 없는 응답(스텁)이면 provider 는 null 이고 두 키 모두 넣지 않는다")
  void 공급자표시_없으면_null() {
    ChatResponseMetadata claude = ChatResponseMetadata.builder().id("msg_1").model("claude-primary").usage(new DefaultUsage(10, 5))
        .keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.CLAUDE.name()).build();
    MarketJudgeClient.JudgeResult primary = stubClient(claude, LlmResponses.FINISH_STOP, FIXTURE).judge("s", payload(), SCHEMA);
    assertThat(primary.provider()).isEqualTo(LlmProvider.CLAUDE);
    assertThat(primary.options()).containsEntry("provider", "CLAUDE").doesNotContainKey("fallbackReason");

    MarketJudgeClient.JudgeResult unmarked = stubClient(ChatResponseMetadata.builder().build(), null, FIXTURE).judge("s", payload(), SCHEMA);
    assertThat(unmarked.provider()).isNull();
    assertThat(unmarked.options()).doesNotContainKeys("provider", "fallbackReason");
    assertThat(unmarked.model()).as("모델명이 없으면 힌트").isEqualTo("hint");
  }

  @Test
  @DisplayName("추론·캐시 토큰은 공급자 중립 LlmTokenUsage 로 읽는다 — 그 타입이 아닌 native usage 면 추론 0, 캐시는 Spring 표준 슬롯")
  void 토큰상세_중립계약() {
    Usage neutral = new DefaultUsage(100, 50, 150, new LlmTokenUsage(7, 11));
    assertThat(MarketJudgeClient.reasoningTokens(neutral)).isEqualTo(7);
    assertThat(MarketJudgeClient.cachedTokens(neutral)).isEqualTo(11);

    Usage standard = new DefaultUsage(100, 50, 150, "raw-native", 40L, null);
    assertThat(MarketJudgeClient.reasoningTokens(standard)).isZero();
    assertThat(MarketJudgeClient.cachedTokens(standard)).isEqualTo(40);
    assertThat(MarketJudgeClient.reasoningTokens(null)).isZero();
    assertThat(MarketJudgeClient.cachedTokens(null)).isZero();
  }

  @Test
  @DisplayName("공급자 구현 패키지(client.openai·client.claude)에 의존하지 않는다 — 중립 계약(client.llm)만 본다")
  void 공급자구현_비의존() {
    ArchRuleDefinition.noClasses().should().dependOnClassesThat().resideInAnyPackage("..advisor.client.openai..", "..advisor.client.claude..")
        .check(new ClassFileImporter().importClasses(MarketJudgeClient.class, MarketJudgeClient.CallResult.class, MarketJudgeClient.JudgeResult.class));
  }

  @Test
  @DisplayName("빈 응답·JSON 아닌 응답은 예외 — 그날 판단을 건너뛴다")
  void 빈응답_비JSON() {
    when(http.create(any())).thenReturn(completed(""));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class).hasMessageContaining("비어");

    when(http.create(any())).thenReturn(completed("not json"));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("JSON 파싱 전에 finishReason 을 본다 — 상한 절단은 답한 공급자와 양쪽 공급자의 상한 설정 키 안내, 기타 미완은 사유, 거부는 거부 문구")
  void finishReason_선검사() {
    when(http.create(any())).thenReturn(response("incomplete", "max_output_tokens", "output_text", "{\"regime\":"));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("출력 토큰 상한").hasMessageContaining("공급자 OPENAI")
        .hasMessageContaining("advisor.model.*-max-completion-tokens").hasMessageContaining("advisor.claude.*-max-tokens");

    when(http.create(any())).thenReturn(response("incomplete", "content_filter", "output_text", ""));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("미완").hasMessageContaining("content_filter");

    when(http.create(any())).thenReturn(response("completed", null, "refusal", "정책상 답할 수 없습니다"));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("거부").hasMessageContaining("정책상 답할 수 없습니다");
  }

  @Test
  @DisplayName("종료 사유는 공급자 중립 계약(LlmResponses)으로 읽는다 — 다른 공급자·공급자 표시 없는 응답의 LENGTH/INCOMPLETE/REFUSAL 도 같은 예외가 된다")
  void finishReason_중립계약() {
    ChatResponseMetadata claude = ChatResponseMetadata.builder().keyValue(LlmResponses.PROVIDER_METADATA_KEY, LlmProvider.CLAUDE.name()).build();
    assertThatThrownBy(() -> stubClient(claude, LlmResponses.FINISH_LENGTH, "{\"regime\":").judge("s", payload(), SCHEMA))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("출력 토큰 상한").hasMessageContaining("공급자 CLAUDE")
        .hasMessageContaining("advisor.claude.*-max-tokens");

    assertThatThrownBy(() -> stubClient(ChatResponseMetadata.builder().build(), LlmResponses.FINISH_LENGTH, "{").judge("s", payload(), SCHEMA))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("출력 토큰 상한").hasMessageNotContaining("공급자");

    ChatResponseMetadata incomplete = ChatResponseMetadata.builder().keyValue(LlmResponses.INCOMPLETE_REASON_METADATA_KEY, "pause_turn").build();
    assertThatThrownBy(() -> stubClient(incomplete, LlmResponses.FINISH_INCOMPLETE, "").judge("s", payload(), SCHEMA))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("미완").hasMessageContaining("pause_turn");

    assertThatThrownBy(() -> stubClient(claude, LlmResponses.FINISH_REFUSAL, "거부 문구").judge("s", payload(), SCHEMA))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("거부").hasMessageContaining("거부 문구");
  }

  private static PromptPayload payload() {
    return new PromptPayload("{}", List.of(), List.of(), 0, false, 1);
  }
}
