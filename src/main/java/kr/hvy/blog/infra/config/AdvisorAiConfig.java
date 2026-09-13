package kr.hvy.blog.infra.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * advisor 모듈의 OpenAI ChatModel 1개 + ChatClient 2종(judge 판단용 / assist 보조용).
 * <p>
 * Spring AI 2.0 의 OpenAI 자동구성(OpenAiChatAutoConfiguration)은 키가 비어 있으면 기동 자체를 막으므로 yml 에서
 * {@code spring.ai.model.chat=none} 으로 끄고, {@code advisor.enabled=true} 일 때만 여기서 직접 조립한다(로컬·테스트는 키 없이 기동).
 * 모델 ID 는 yml(advisor.model.*)에서만 오고 코드에 박지 않는다(ClaudeCodeRefreshService 의 하드코딩 모델이 은퇴로 404 를 냈던 전례).
 * 재시도는 OpenAI SDK 의 maxRetries(429·5xx·타임아웃)가 담당하고, 소진되면 잡이 그날 판단을 건너뛴다.
 */
@Configuration
@ConditionalOnProperty(name = "advisor.enabled", havingValue = "true")
public class AdvisorAiConfig {

  public static final String JUDGE = "judgeChatClient";
  public static final String ASSIST = "assistChatClient";

  /**
   * OpenAI 공식 SDK 클라이언트 위의 Spring AI ChatModel. HTTP 는 Spring AI 가 제공하는 OkHttp 래퍼(관측 연동)를 쓴다.
   * 키가 비어 있으면 빈 생성 시점에 실패시키지 않고 호출 시점에 401 로 드러나게 둔다 — 기동 실패보다 잡 단위 실패가 낫다
   * (AdvisorProperties.isConfigured 가 트리거 전에 거부하므로 실제로는 호출까지 가지 않는다).
   */
  @Bean("advisorChatModel")
  public OpenAiChatModel advisorChatModel(AdvisorProperties properties, ObjectProvider<ObservationRegistry> observationRegistry,
      ObjectProvider<MeterRegistry> meterRegistry) {
    AdvisorProperties.Model model = properties.getModel();
    Duration timeout = Duration.ofSeconds(model.getTimeoutSeconds());
    ObservationRegistry observations = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);

    SpringAiOpenAiHttpClient.Builder http = SpringAiOpenAiHttpClient.builder()
        .timeout(timeout)
        .observationRegistry(observations);
    MeterRegistry meters = meterRegistry.getIfAvailable();
    if (meters != null) {
      http.meterRegistry(meters);
    }
    ClientOptions options = ClientOptions.builder()
        .httpClient(http.build())
        .apiKey(properties.openAiApiKey())
        .timeout(timeout)
        .maxRetries(model.getMaxRetries())
        .build();
    OpenAIClient client = new OpenAIClientImpl(options);

    OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
        .openAiClient(client)
        .options(chatOptions(model.getJudge(), model.getJudgeMaxCompletionTokens(), model.getJudgeTemperature()).build())
        .observationRegistry(observations);
    if (meters != null) {
      builder.meterRegistry(meters);
    }
    return builder.build();
  }

  /**
   * 판단용 ChatClient. 출력 상한은 추론 토큰을 포함하는 maxCompletionTokens 로 건다(maxTokens 와 배타).
   */
  @Bean(JUDGE)
  public ChatClient judgeChatClient(OpenAiChatModel advisorChatModel, AdvisorProperties properties) {
    AdvisorProperties.Model model = properties.getModel();
    return ChatClient.builder(advisorChatModel)
        .defaultOptions(chatOptions(model.getJudge(), model.getJudgeMaxCompletionTokens(), model.getJudgeTemperature()))
        .build();
  }

  /**
   * 보조용 ChatClient (채점 요약·교훈 후보 생성·재현성 측정) — 저가 모델.
   */
  @Bean(ASSIST)
  public ChatClient assistChatClient(OpenAiChatModel advisorChatModel, AdvisorProperties properties) {
    AdvisorProperties.Model model = properties.getModel();
    return ChatClient.builder(advisorChatModel)
        .defaultOptions(chatOptions(model.getAssist(), model.getAssistMaxCompletionTokens(), model.getAssistTemperature()))
        .build();
  }

  /**
   * 모델별 기본 옵션 빌더. temperature 는 null 이 아닐 때만 넣는다(추론 모델은 temperature 를 거부한다).
   */
  static OpenAiChatOptions.Builder chatOptions(String model, int maxCompletionTokens, Double temperature) {
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
        .model(model)
        .maxCompletionTokens(maxCompletionTokens);
    if (temperature != null) {
      builder.temperature(temperature);
    }
    return builder;
  }
}
