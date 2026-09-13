package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.mock.env.MockEnvironment;

/**
 * 실계정 수동 실측 (OPENAI_API_KEY·ADVISOR_JUDGE_MODEL 환경변수가 있을 때만). strict JSON 스키마 수용·구조화 출력 파싱·토큰·지연을 확인한다.
 * <pre>OPENAI_API_KEY=... ADVISOR_JUDGE_MODEL=... ./gradlew test --tests "kr.hvy.blog.modules.advisor.application.service.AdvisorOpenAiManualTest"</pre>
 */
class AdvisorOpenAiManualTest {

  @Test
  @DisplayName("실제 모델 1회: 후보 enum 스키마를 받아들이고 후보 안에서만 픽을 낸다")
  void realModelAcceptsSchema() {
    String apiKey = System.getenv("OPENAI_API_KEY");
    String model = System.getenv("ADVISOR_JUDGE_MODEL");
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 없음 — 수동 실측 스킵");
    Assumptions.assumeTrue(model != null && !model.isBlank(), "ADVISOR_JUDGE_MODEL 없음 — 수동 실측 스킵");

    ClientOptions options = ClientOptions.builder()
        .httpClient(SpringAiOpenAiHttpClient.builder().timeout(Duration.ofSeconds(120)).build())
        .apiKey(apiKey).timeout(Duration.ofSeconds(120)).maxRetries(1).build();
    OpenAiChatModel chatModel = OpenAiChatModel.builder().openAiClient(new OpenAIClientImpl(options))
        .options(OpenAiChatOptions.builder().model(model).maxCompletionTokens(4000).build()).build();
    ChatClient chatClient = ChatClient.builder(chatModel).build();

    AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
    AdvicePromptBuilder builder = new AdvicePromptBuilder(properties);
    PromptPayload payload = builder.build(AdvicePromptBuilderTest.market(), AdvicePromptBuilderTest.screening(8), null, List.of(),
        Map.of("MOM_20D", 0.12, "FOREIGN_FLOW", 0.12), kr.hvy.blog.modules.advisor.domain.code.DataQuality.OK);
    String schema = AdviceSchemaFactory.schemaJson(payload.candidateTickers(), payload.sectorCodes());

    MarketJudgeClient client = new MarketJudgeClient(chatClient, model);
    MarketJudgeClient.JudgeResult result = client.judge(new PromptResources().adviceSystem(), payload, schema);

    System.out.println("=== 응답 ===\n" + result.rawText());
    System.out.println("=== usage in/out/reasoning/cached: " + result.usage().getPromptTokens() + "/" + result.usage().getCompletionTokens()
        + "/" + result.reasoningTokens() + "/" + result.cachedTokens() + ", " + result.options());
    assertThat(result.response().picks()).isNotEmpty();
    assertThat(result.response().picks()).allMatch(p -> payload.candidateTickers().contains(p.ticker()));
    assertThat(result.response().picks()).allMatch(p -> AdviceSchemaFactory.CONVICTIONS.contains(p.conviction()));
    // advice-v2: 2단계 중첩 객체(trendOutlook.kospi.invalidation)를 strict 가 수용하는지가 실측의 핵심
    assertThat(result.response().trendOutlook()).as("trendOutlook 블록").isNotNull();
    assertThat(result.response().trendOutlook().kospi().persist()).isIn("WITHIN_5D", "ABOUT_20D", "BEYOND_20D");
    assertThat(result.response().trendOutlook().kospi().invalidation()).isIn("NONE", "BELOW_MA20", "BELOW_MA60", "ABOVE_MA20", "ABOVE_MA60");
    assertThat(result.usage().getPromptTokens()).isLessThan(8000);
  }
}
