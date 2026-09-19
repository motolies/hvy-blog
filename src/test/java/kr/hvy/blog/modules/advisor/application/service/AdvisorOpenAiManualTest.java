package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiBearerAuthInterceptor;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesClient;
import kr.hvy.blog.modules.advisor.client.openai.ResponsesChatOptions;
import kr.hvy.blog.modules.advisor.domain.model.PromptPayload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/**
 * 실계정 수동 실측 (OPENAI_API_KEY·ADVISOR_JUDGE_MODEL 환경변수가 있을 때만). Responses API {@code text.format} 이 strict JSON 스키마(후보 enum·
 * advice-v2 의 2단계 중첩 trendOutlook)를 받아들이는지·구조화 출력 파싱·토큰·지연을 확인한다. 조립은 운영 AdvisorAiConfig 의 judge 모델과 같고
 * RestClient 만 api_log 인터셉터 없이 인증 인터셉터 + 읽기 타임아웃(추론 모델은 수십 초)으로 만든다.
 * <pre>OPENAI_API_KEY=... ADVISOR_JUDGE_MODEL=... ./gradlew test --tests "kr.hvy.blog.modules.advisor.application.service.AdvisorOpenAiManualTest"</pre>
 */
class AdvisorOpenAiManualTest {

  @Test
  @DisplayName("실제 모델 1회: Responses text.format 이 후보 enum strict 스키마를 받아들이고 후보 안에서만 픽을 낸다")
  void realModelAcceptsSchema() {
    String apiKey = System.getenv("OPENAI_API_KEY");
    String model = System.getenv("ADVISOR_JUDGE_MODEL");
    Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 없음 — 수동 실측 스킵");
    Assumptions.assumeTrue(model != null && !model.isBlank(), "ADVISOR_JUDGE_MODEL 없음 — 수동 실측 스킵");

    HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(180)); // 기본 팩토리는 무한 대기 — 20k 토큰 judge 가 매달릴 수 있다(연결 타임아웃은 Spring 7 에서 HttpClient 쪽 설정)
    RestClient restClient = RestClient.builder().baseUrl("https://api.openai.com").requestFactory(factory)
        .requestInterceptor(new OpenAiBearerAuthInterceptor(() -> apiKey)).build();
    OpenAiResponsesChatModel chatModel = new OpenAiResponsesChatModel(new OpenAiResponsesClient(restClient, 1),
        ResponsesChatOptions.builder().model(model).maxTokens(8000).build(), 0);
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
    // advice-v2: 2단계 중첩 객체(trendOutlook.kospi.invalidation)를 Responses strict text.format 이 수용하는지가 실측의 핵심
    assertThat(result.response().trendOutlook()).as("trendOutlook 블록").isNotNull();
    assertThat(result.response().trendOutlook().kospi().persist()).isIn("WITHIN_5D", "ABOUT_20D", "BEYOND_20D");
    assertThat(result.response().trendOutlook().kospi().invalidation()).isIn("NONE", "BELOW_MA20", "BELOW_MA60", "ABOVE_MA20", "ABOVE_MA60");
    assertThat(result.usage().getPromptTokens()).isLessThan(8000);
  }
}
