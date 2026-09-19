package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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

/**
 * 가짜 HTTP 클라이언트 위의 <b>진짜</b> Responses 모델 + ChatClient 로 호출 규약을 고정한다: 시스템·사용자 메시지, strict text.format, 모델 기본 옵션 보존,
 * 응답 파싱·usage, finishReason(LENGTH/INCOMPLETE/REFUSAL) 선검사. 이전의 람다 ChatModel 스텁은 옵션 타입을 몰라 "assist 가 judge 모델로 나가는" 버그를 못 잡았다.
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
    assertThat(request.model()).as(".options(textFormat) 가 모델 기본 옵션을 버리면 안 된다(assist→judge 오배선 회귀)").isEqualTo("assist-model");
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
    assertThat(result.reasoningTokens()).as("단발 호출은 ToolCallingAdvisor 합산을 거쳐도 native ResponsesUsage 가 보존된다").isEqualTo(300);
    assertThat(result.cachedTokens()).isEqualTo(100);
    assertThat(result.model()).isEqualTo("assist-model-2026");
    assertThat(result.responseId()).isEqualTo("resp_1");
    assertThat(result.rawText()).isEqualTo(FIXTURE);
    assertThat(result.options()).containsEntry("responseFormat", "json_schema/strict").containsEntry("schemaName", "AdviceResponse");
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
  @DisplayName("JSON 파싱 전에 finishReason 을 본다 — 상한 절단은 max_output_tokens 안내, 기타 미완은 사유, 거부는 거부 문구")
  void finishReason_선검사() {
    when(http.create(any())).thenReturn(response("incomplete", "max_output_tokens", "output_text", "{\"regime\":"));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max_output_tokens").hasMessageContaining("max-completion-tokens");

    when(http.create(any())).thenReturn(response("incomplete", "content_filter", "output_text", ""));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("미완").hasMessageContaining("content_filter");

    when(http.create(any())).thenReturn(response("completed", null, "refusal", "정책상 답할 수 없습니다"));
    assertThatThrownBy(() -> client.judge("s", payload(), SCHEMA)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("거부").hasMessageContaining("정책상 답할 수 없습니다");
  }

  private static PromptPayload payload() {
    return new PromptPayload("{}", List.of(), List.of(), 0, false, 1);
  }
}
