package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ChatModel 스텁으로 호출 규약을 고정한다: 시스템·사용자 메시지 전달, strict JSON 스키마 옵션, 응답 파싱·usage 기록, 빈 응답 예외.
 */
class MarketJudgeClientTest {

  static final String FIXTURE = """
      {"regime":{"code":"RISK_ON","kospiDir":"UP","kosdaqDir":"NEUTRAL","pUp":"0.70","rationale":"근거"},
       "sectors":[{"code":"G2510","reason":"반도체"}],
       "picks":[{"ticker":"005930","direction":"LONG","conviction":"0.80","thesis":"t","risk":"r","citedFeatures":[{"name":"r20","value":0.081}]}],
       "summary":"요약"}
      """;

  @Test
  @DisplayName("시스템·사용자 메시지가 그대로 전달되고 응답 JSON 이 레코드로 파싱되며 usage 가 기록된다")
  void parsesResponseAndRecordsUsage() {
    AtomicReference<Prompt> captured = new AtomicReference<>();
    ChatModel stub = prompt -> {
      captured.set(prompt);
      ChatResponseMetadata metadata = ChatResponseMetadata.builder().id("resp_1").model("judge-model").usage(new DefaultUsage(6000, 1900)).build();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(FIXTURE))), metadata);
    };
    MarketJudgeClient client = new MarketJudgeClient(ChatClient.create(stub), "hint");
    PromptPayload payload = new PromptPayload("{\"asOf\":\"2026-09-11\"}", List.of("005930"), List.of("G2510"), 1, false, 10);

    MarketJudgeClient.JudgeResult result = client.judge("SYSTEM PROMPT", payload, AdviceSchemaFactory.schemaJson(List.of("005930"), List.of("G2510")));

    assertThat(result.response().picks()).hasSize(1);
    assertThat(result.response().picks().getFirst().ticker()).isEqualTo("005930");
    assertThat(result.response().regime().pUp()).isEqualTo("0.70");
    assertThat(result.usage().getPromptTokens()).isEqualTo(6000);
    assertThat(result.usage().getCompletionTokens()).isEqualTo(1900);
    assertThat(result.reasoningTokens()).isZero();
    assertThat(result.model()).isEqualTo("judge-model");
    assertThat(result.responseId()).isEqualTo("resp_1");
    assertThat(result.rawText()).isEqualTo(FIXTURE);

    Prompt prompt = captured.get();
    assertThat(prompt.getInstructions()).hasSize(2);
    assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("SYSTEM PROMPT");
    assertThat(prompt.getInstructions().get(1).getText()).isEqualTo("{\"asOf\":\"2026-09-11\"}");
    // 스텁 ChatModel 은 OpenAI 옵션 타입을 모르므로 ChatClient 가 공통 옵션으로 접는다 — 응답 형식(strict 스키마)은 수동 실측(AdvisorOpenAiManualTest)이 확인한다
    assertThat(prompt.getOptions()).isNotNull();
  }

  @Test
  @DisplayName("빈 응답·JSON 아닌 응답은 예외 — 그날 판단을 건너뛴다")
  void rejectsEmptyOrInvalid() {
    ChatModel empty = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
    assertThatThrownBy(() -> new MarketJudgeClient(ChatClient.create(empty), "m").judge("s", payload(), "{}"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("비어");
    ChatModel garbage = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("not json"))));
    assertThatThrownBy(() -> new MarketJudgeClient(ChatClient.create(garbage), "m").judge("s", payload(), "{}"))
        .isInstanceOf(RuntimeException.class);
  }

  private static PromptPayload payload() {
    return new PromptPayload("{}", List.of(), List.of(), 0, false, 1);
  }
}
