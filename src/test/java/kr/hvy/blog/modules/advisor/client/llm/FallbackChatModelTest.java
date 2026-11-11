package kr.hvy.blog.modules.advisor.client.llm;

import static kr.hvy.blog.modules.advisor.client.llm.LlmProviderGateTest.failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderGateTest.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 폴백 래퍼의 라우팅 규칙. 공급자 모델은 호출을 기록하는 스텁이고 게이트는 진짜다(호출 횟수만 세는 하위 클래스) — 래퍼와 게이트가 맞물리는 지점
 * (차단 진입·프로브·거부 시 미기록)을 실제 조합으로 본다.
 */
@DisplayName("FallbackChatModel - 1순위 → 폴백 라우팅 규칙")
class FallbackChatModelTest {

  static final LlmProvider CLAUDE = LlmProvider.CLAUDE;
  static final LlmProvider OPENAI = LlmProvider.OPENAI;
  /** 래퍼가 게이트에 넘기는 1순위 모델 ID */
  static final String PRIMARY_MODEL = "claude-judge";

  /** 호출을 기록하고 지정한 응답·예외를 내는 공급자 모델 스텁 */
  static final class StubModel implements ChatModel {

    final List<Prompt> prompts = new ArrayList<>();
    Function<Prompt, ChatResponse> behavior;

    StubModel(ChatResponse answer) {
      answer(answer);
    }

    void answer(ChatResponse response) {
      this.behavior = prompt -> response;
    }

    void fail(RuntimeException exception) {
      this.behavior = prompt -> {
        throw exception;
      };
    }

    int calls() {
      return prompts.size();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      return behavior.apply(prompt);
    }
  }

  /** 진짜 게이트 + 기록 횟수. recordSuccess 는 상태로는 "만료된 기록" 과 구분되지 않아 횟수로 본다 */
  static final class CountingGate extends LlmProviderGate {

    int successes;
    int failures;

    CountingGate(Clock clock, AlertSink alertSink) {
      super(clock, Duration.ofMinutes(60), Duration.ofMinutes(5), Duration.ofMinutes(30), alertSink);
    }

    @Override
    public void recordSuccess(long callToken, String... scopes) {
      successes++;
      super.recordSuccess(callToken, scopes);
    }

    @Override
    public void recordFailure(LlmProviderException failure, String model, String unansweredScope) {
      failures++;
      super.recordFailure(failure, model, unansweredScope);
    }
  }

  static class Tools {

    final List<String> invocations = new ArrayList<>();

    @Tool(name = "echo", description = "echo")
    public String echo(String v) {
      invocations.add(v);
      return v;
    }
  }

  /** 공급자 모델의 의무대로 AssistantMessage 메타데이터에 공급자를 실은 메시지. provider 가 null 이면 표시 없는 메시지(Slack 히스토리) */
  static AssistantMessage assistant(LlmProvider provider, String text, AssistantMessage.ToolCall... toolCalls) {
    Map<String, Object> metadata = provider == null ? Map.of() : Map.of(LlmResponses.PROVIDER_METADATA_KEY, provider.name());
    return AssistantMessage.builder().content(text).properties(metadata).toolCalls(List.of(toolCalls)).media(List.of()).build();
  }

  static ChatResponse response(LlmProvider provider, String finishReason, AssistantMessage assistant) {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder().id("id-" + provider).model("model-" + provider)
        .usage(new DefaultUsage(10, 20, 30)).keyValue(LlmResponses.PROVIDER_METADATA_KEY, provider.name()).build();
    return new ChatResponse(List.of(new Generation(assistant, ChatGenerationMetadata.builder().finishReason(finishReason).build())), metadata);
  }

  static ChatResponse response(LlmProvider provider, String finishReason, String text) {
    return response(provider, finishReason, assistant(provider, text));
  }

  static AssistantMessage.ToolCall echoCall(String id) {
    return new AssistantMessage.ToolCall(id, "function", "echo", "{\"v\":\"a\"}");
  }

  static ToolResponseMessage toolResult(String id) {
    return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id, "echo", "\"a\""))).build();
  }

  static final Prompt QUESTION = new Prompt(new UserMessage("질문"));

  final MutableClock clock = new MutableClock(LlmProviderGateTest.START);
  final List<String> alerts = new ArrayList<>();
  final CountingGate gate = new CountingGate(clock, (text, error) -> alerts.add(text));
  final ChatResponse claudeAnswer = response(CLAUDE, LlmResponses.FINISH_STOP, "claude 답");
  final ChatResponse openAiAnswer = response(OPENAI, LlmResponses.FINISH_STOP, "gpt 답");
  final ChatResponse claudeRefusal = response(CLAUDE, LlmResponses.FINISH_REFUSAL, "답할 수 없습니다");
  final StubModel primary = new StubModel(claudeAnswer);
  final StubModel fallback = new StubModel(openAiAnswer);
  boolean primaryReady = true;
  boolean fallbackReady = true;
  final FallbackChatModel model = new FallbackChatModel(CLAUDE, PRIMARY_MODEL, primary, () -> primaryReady, fallback, () -> fallbackReady, gate);

  @Nested
  @DisplayName("준비 상태")
  class Readiness {

    @Test
    @DisplayName("둘 다 준비 안 됨 → IllegalStateException(설정 안내), 어느 쪽도 부르지 않는다")
    void 둘다_미준비() {
      primaryReady = false;
      fallbackReady = false;

      assertThatThrownBy(() -> model.call(QUESTION)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("준비되지 않았습니다").hasMessageContaining("CLAUDE");
      assertThat(primary.calls()).isZero();
      assertThat(fallback.calls()).isZero();
    }

    @Test
    @DisplayName("폴백만 준비됨 → 폴백 응답을 손대지 않고 그대로 — 폴백이 아니라 정상 경로라 사유를 싣지 않고 게이트도 건드리지 않는다")
    void 폴백만_준비() {
      primaryReady = false;

      ChatResponse response = model.call(QUESTION);

      assertThat(response).isSameAs(openAiAnswer);
      assertThat(LlmResponses.fallbackReason(response)).isNull();
      assertThat(primary.calls()).isZero();
      assertThat(gate.successes + gate.failures).isZero();
    }

    @Test
    @DisplayName("1순위만 준비됨 → 실패를 그대로 던지되 게이트에 기록해 알린다. 차단 중이어도 1순위를 계속 부른다(넘길 곳이 없다)")
    void 일순위만_준비_실패() {
      fallbackReady = false;
      LlmProviderException failure = failure(LlmFailureKind.MISCONFIGURED, 401, "invalid x-api-key");
      primary.fail(failure);

      assertThatThrownBy(() -> model.call(QUESTION)).isSameAs(failure);
      assertThat(gate.isBlocked(PRIMARY_MODEL)).isTrue();
      assertThat(alerts).hasSize(1);

      assertThatThrownBy(() -> model.call(QUESTION)).isSameAs(failure);
      assertThat(primary.calls()).as("차단 여부를 보지 않는다").isEqualTo(2);
      assertThat(fallback.calls()).isZero();
      assertThat(alerts).as("알림은 하루 1통").hasSize(1);
    }

    @Test
    @DisplayName("1순위만 준비됨 → 성공하면 게이트의 차단 기록을 지운다(게이트를 공유하는 다른 역할의 라우팅에 쓰인다)")
    void 일순위만_준비_성공() {
      fallbackReady = false;
      gate.recordFailure(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"), PRIMARY_MODEL, null);

      ChatResponse response = model.call(QUESTION);

      assertThat(response).isSameAs(claudeAnswer);
      assertThat(gate.successes).isEqualTo(1);
      assertThat(gate.isBlocked(PRIMARY_MODEL)).isFalse();
    }

    @Test
    @DisplayName("1순위만 준비됨 → 거부 응답도 그대로 돌려준다(게이트 미기록)")
    void 일순위만_준비_거부() {
      fallbackReady = false;
      primary.answer(claudeRefusal);

      assertThat(model.call(QUESTION)).isSameAs(claudeRefusal);
      assertThat(gate.successes + gate.failures).isZero();
    }

    @Test
    @DisplayName("준비 여부는 호출마다 평가한다 — 키가 나중에 채워지면 다음 호출부터 그 공급자를 쓴다")
    void 호출마다_평가() {
      primaryReady = false;
      fallbackReady = false;
      assertThatThrownBy(() -> model.call(QUESTION)).isInstanceOf(IllegalStateException.class);

      fallbackReady = true;
      assertThat(model.call(QUESTION)).isSameAs(openAiAnswer);

      primaryReady = true;
      assertThat(model.call(QUESTION)).isSameAs(claudeAnswer);
    }
  }

  @Nested
  @DisplayName("둘 다 준비됨 — 1순위 호출과 폴백")
  class Routing {

    @Test
    @DisplayName("정상 응답 → 1순위 응답 그대로, 폴백을 부르지 않고 게이트에 성공을 기록한다")
    void 정상() {
      ChatResponse response = model.call(QUESTION);

      assertThat(response).isSameAs(claudeAnswer);
      assertThat(LlmResponses.fallbackReason(response)).isNull();
      assertThat(fallback.calls()).isZero();
      assertThat(gate.successes).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureKind.class, names = "REQUEST_INVALID", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("폴백 대상 분류의 실패 → 게이트에 기록하고 폴백 호출(사유 = 분류 이름). 다음 호출은 1순위를 부르지 않는다(BLOCKED:<분류>)")
    void 분류별_예외_폴백(LlmFailureKind kind) {
      primary.fail(failure(kind, 500, "boom"));

      ChatResponse first = model.call(QUESTION);

      assertThat(LlmResponses.fallbackReason(first)).isEqualTo(kind.name());
      assertThat(LlmResponses.provider(first)).isEqualTo(OPENAI);
      assertThat(gate.currentBlock(PRIMARY_MODEL).orElseThrow().kind()).isEqualTo(kind);
      assertThat(alerts).hasSize(1);

      ChatResponse second = model.call(QUESTION);

      assertThat(LlmResponses.fallbackReason(second)).isEqualTo(FallbackChatModel.REASON_BLOCKED_PREFIX + kind.name())
          .isEqualTo(FallbackChatModel.blockedReason(kind));
      assertThat(primary.calls()).as("차단 중에는 1순위를 부르지 않는다").isEqualTo(1);
      assertThat(fallback.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("차단이 만료되면 다음 호출이 1순위를 다시 시도하고(프로브), 성공하면 차단 기록이 지워진다")
    void 차단_만료_후_프로브() {
      gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), PRIMARY_MODEL, null);
      assertThat(LlmResponses.fallbackReason(model.call(QUESTION))).isEqualTo("BLOCKED:EXHAUSTED");
      assertThat(primary.calls()).isZero();

      clock.advance(Duration.ofMinutes(60));
      ChatResponse probe = model.call(QUESTION);

      assertThat(probe).isSameAs(claudeAnswer);
      assertThat(primary.calls()).isEqualTo(1);
      assertThat(gate.successes).isEqualTo(1);
    }

    @Test
    @DisplayName("REQUEST_INVALID 는 폴백하지 않고 그대로 던진다 — 게이트도 차단하지 않는다")
    void 요청오류_전파() {
      LlmProviderException invalid = failure(LlmFailureKind.REQUEST_INVALID, 400, "messages: at least one message is required");
      primary.fail(invalid);

      assertThatThrownBy(() -> model.call(QUESTION)).isSameAs(invalid);
      assertThat(fallback.calls()).isZero();
      assertThat(gate.isBlocked(PRIMARY_MODEL)).isFalse();
      assertThat(alerts).isEmpty();
    }

    @Test
    @DisplayName("LlmProviderException 이 아닌 예외는 그대로 던진다 — 폴백이 버그를 가리지 않는다")
    void 그밖의_예외_전파() {
      IllegalArgumentException bug = new IllegalArgumentException("요청 조립 버그");
      primary.fail(bug);

      assertThatThrownBy(() -> model.call(QUESTION)).isSameAs(bug);
      assertThat(fallback.calls()).isZero();
      assertThat(gate.successes + gate.failures).isZero();
    }

    @Test
    @DisplayName("양쪽 다 실패 → 폴백의 예외를 던지고 1순위 예외를 suppressed 로 붙인다")
    void 양쪽_실패() {
      LlmProviderException claudeFailure = failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded");
      IllegalStateException openAiFailure = new IllegalStateException("OpenAI 503");
      primary.fail(claudeFailure);
      fallback.fail(openAiFailure);

      assertThatThrownBy(() -> model.call(QUESTION)).isSameAs(openAiFailure);
      assertThat(openAiFailure.getSuppressed()).containsExactly(claudeFailure);
      assertThat(gate.isBlocked(PRIMARY_MODEL)).as("1순위 실패는 기록됐다").isTrue();
    }

    @Test
    @DisplayName("1순위를 부르지 않고 넘긴 폴백(BLOCKED)이 실패하면 폴백의 예외만 — 붙일 1순위 예외가 없다")
    void 차단중_폴백_실패() {
      gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), PRIMARY_MODEL, null);
      IllegalStateException openAiFailure = new IllegalStateException("OpenAI 503");
      fallback.fail(openAiFailure);

      assertThatThrownBy(() -> model.call(QUESTION)).isSameAs(openAiFailure);
      assertThat(openAiFailure.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("1순위 거부(REFUSAL) → 폴백 호출(사유 REFUSAL). 요청 내용 탓이라 게이트에 성공도 실패도 기록하지 않고, 다음 호출은 다시 1순위부터다")
    void 거부_폴백() {
      primary.answer(claudeRefusal);

      ChatResponse response = model.call(QUESTION);

      assertThat(LlmResponses.fallbackReason(response)).isEqualTo(FallbackChatModel.REASON_REFUSAL);
      assertThat(response.getResult().getOutput().getText()).isEqualTo("gpt 답");
      assertThat(gate.successes + gate.failures).isZero();
      assertThat(gate.isBlocked(PRIMARY_MODEL)).isFalse();
      assertThat(alerts).isEmpty();

      model.call(QUESTION);
      assertThat(primary.calls()).as("차단이 없으므로 다시 1순위").isEqualTo(2);
    }

    @Test
    @DisplayName("거부로 넘어간 폴백 응답의 usage 에는 거부된 1순위 응답의 usage 가 더해진다(거부도 과금) — AssistantMessage 인스턴스·사유·id·model 은 그대로")
    void 거부_폴백_사용량_합산() {
      ChatResponse refusal = new ChatResponse(claudeRefusal.getResults(), ChatResponseMetadata.builder().id("id-CLAUDE").model("model-CLAUDE")
          .usage(new DefaultUsage(100, 7, 107, new LlmTokenUsage(5, 40), 40L, null)).keyValue(LlmResponses.PROVIDER_METADATA_KEY, CLAUDE.name()).build());
      ChatResponse answer = new ChatResponse(openAiAnswer.getResults(), ChatResponseMetadata.builder().id("id-OPENAI").model("model-OPENAI")
          .usage(new DefaultUsage(10, 20, 30, new LlmTokenUsage(3, 2), 2L, null)).keyValue(LlmResponses.PROVIDER_METADATA_KEY, OPENAI.name()).build());
      primary.answer(refusal);
      fallback.answer(answer);

      ChatResponse response = model.call(QUESTION);

      assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(110);
      assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(27);
      assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(137);
      assertThat(response.getMetadata().getUsage().getCacheReadInputTokens()).isEqualTo(42L);
      assertThat(response.getMetadata().getUsage().getCacheWriteInputTokens()).as("양쪽 다 보고하지 않은 값은 null 그대로").isNull();
      assertThat(response.getMetadata().getUsage().getNativeUsage()).as("추론·캐시 토큰도 합산 — Spring AI 의 합산은 native usage 를 버린다")
          .isEqualTo(new LlmTokenUsage(8, 42));
      assertThat(response.getResult().getOutput()).as("도구 루프 히스토리가 같은 인스턴스를 본다").isSameAs(answer.getResult().getOutput());
      assertThat(LlmResponses.fallbackReason(response)).isEqualTo(FallbackChatModel.REASON_REFUSAL);
      assertThat(LlmResponses.provider(response)).isEqualTo(OPENAI);
      assertThat(response.getMetadata().getId()).isEqualTo("id-OPENAI");
      assertThat(response.getMetadata().getModel()).isEqualTo("model-OPENAI");
      assertThat(answer.getMetadata().getUsage().getTotalTokens()).as("폴백 모델이 돌려준 원본 응답은 바뀌지 않는다").isEqualTo(30);
    }

    @Test
    @DisplayName("늦은 성공: 1순위 호출이 도는 동안 다른 역할이 기록한 차단은 이 호출의 성공이 지우지 않는다")
    void 늦은_성공은_차단을_지우지_않는다() {
      primary.behavior = prompt -> {
        // 이 호출이 응답을 기다리는 사이 게이트를 공유하는 다른 역할이 소진을 겪었다
        gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), "claude-chat", null);
        return claudeAnswer;
      };

      ChatResponse response = model.call(QUESTION);

      assertThat(response).isSameAs(claudeAnswer);
      assertThat(gate.currentBlock(PRIMARY_MODEL).orElseThrow().kind()).isEqualTo(LlmFailureKind.EXHAUSTED);
      assertThat(LlmResponses.fallbackReason(model.call(QUESTION))).as("다음 호출은 차단을 따른다").isEqualTo("BLOCKED:EXHAUSTED");
    }

    @Test
    @DisplayName("채팅 래퍼의 응답 없음(짧은 타임아웃)은 채팅만 차단한다 — 같은 모델·같은 게이트를 쓰는 판단 래퍼는 계속 1순위를 부른다")
    void 채팅의_응답없음은_채팅만_차단() {
      StubModel chatPrimary = new StubModel(claudeAnswer);
      chatPrimary.fail(failure(LlmFailureKind.UNAVAILABLE, 0, "응답 없음(타임아웃·연결 실패): Read timed out"));
      StubModel chatFallback = new StubModel(openAiAnswer);
      // 판단 래퍼(model)와 같은 모델 ID — 모델 단위로는 가를 수 없고 응답 없음 범위로만 갈린다
      FallbackChatModel chatModel = new FallbackChatModel(CLAUDE, PRIMARY_MODEL, "채팅 봇", chatPrimary, () -> true, chatFallback, () -> true, gate);

      assertThat(LlmResponses.fallbackReason(chatModel.call(QUESTION))).isEqualTo("UNAVAILABLE");
      assertThat(LlmResponses.fallbackReason(chatModel.call(QUESTION))).isEqualTo("BLOCKED:UNAVAILABLE");
      assertThat(chatPrimary.calls()).as("채팅은 5분간 Claude 를 건너뛴다").isEqualTo(1);

      assertThat(model.call(QUESTION)).as("판단 래퍼는 차단되지 않는다").isSameAs(claudeAnswer);
      assertThat(primary.calls()).isEqualTo(1);
      assertThat(gate.isBlocked(PRIMARY_MODEL, "채팅 봇")).as("판단의 성공이 채팅 범위 차단을 풀지 않는다").isTrue();

      clock.advance(Duration.ofMinutes(5));
      chatPrimary.answer(claudeAnswer);
      assertThat(chatModel.call(QUESTION)).as("만료 뒤 채팅의 프로브가 성공하면 풀린다").isSameAs(claudeAnswer);
      assertThat(gate.isBlocked(PRIMARY_MODEL, "채팅 봇")).isFalse();
    }

    @Test
    @DisplayName("채팅 래퍼라도 서버가 답한 장애(529)는 계정 단위로 차단한다 — 판단 래퍼도 폴백으로 간다")
    void 채팅의_서버_장애는_계정_단위() {
      StubModel chatPrimary = new StubModel(claudeAnswer);
      chatPrimary.fail(failure(LlmFailureKind.UNAVAILABLE, 529, "overloaded"));
      FallbackChatModel chatModel = new FallbackChatModel(CLAUDE, PRIMARY_MODEL, "채팅 봇", chatPrimary, () -> true, new StubModel(openAiAnswer),
          () -> true, gate);

      chatModel.call(QUESTION);

      assertThat(LlmResponses.fallbackReason(model.call(QUESTION))).isEqualTo("BLOCKED:UNAVAILABLE");
      assertThat(primary.calls()).isZero();
    }

    @Test
    @DisplayName("모델 없음(404)은 그 모델을 쓰는 래퍼만 차단한다 — 같은 게이트를 쓰는 다른 모델의 래퍼는 계속 1순위를 부른다")
    void 모델없음은_그_래퍼만_차단() {
      StubModel chatPrimary = new StubModel(claudeAnswer);
      chatPrimary.fail(failure(LlmFailureKind.MISCONFIGURED, 404, "model: claude-chat"));
      StubModel chatFallback = new StubModel(openAiAnswer);
      FallbackChatModel chatModel = new FallbackChatModel(CLAUDE, "claude-chat", chatPrimary, () -> true, chatFallback, () -> true, gate);

      assertThat(LlmResponses.fallbackReason(chatModel.call(QUESTION))).isEqualTo("MISCONFIGURED");
      assertThat(LlmResponses.fallbackReason(chatModel.call(QUESTION))).isEqualTo("BLOCKED:MISCONFIGURED");
      assertThat(chatPrimary.calls()).as("채팅 래퍼는 차단 중").isEqualTo(1);

      ChatResponse judge = model.call(QUESTION);

      assertThat(judge).as("판단 래퍼는 1순위 그대로").isSameAs(claudeAnswer);
      assertThat(primary.calls()).isEqualTo(1);
      assertThat(gate.isBlocked("claude-chat")).as("판단 모델의 성공이 채팅 모델의 차단을 지우지 않는다").isTrue();
    }

    @Test
    @DisplayName("폴백 응답에는 사유만 더한다 — AssistantMessage 인스턴스·공급자·id·model·usage·finishReason 은 그대로이고 원본 응답은 바뀌지 않는다")
    void 폴백_응답_보존() {
      primary.fail(failure(LlmFailureKind.EXHAUSTED, 402, "credit"));

      ChatResponse response = model.call(QUESTION);

      assertThat(response.getResult().getOutput()).as("도구 루프 히스토리가 같은 인스턴스를 본다").isSameAs(openAiAnswer.getResult().getOutput());
      assertThat(LlmResponses.provider(response)).isEqualTo(OPENAI);
      assertThat(LlmResponses.provider(response.getResult().getOutput())).isEqualTo(OPENAI);
      assertThat(response.getMetadata().getId()).isEqualTo("id-OPENAI");
      assertThat(response.getMetadata().getModel()).isEqualTo("model-OPENAI");
      assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(30);
      assertThat(LlmResponses.finishReason(response)).isEqualTo(LlmResponses.FINISH_STOP);
      assertThat(LlmResponses.fallbackReason(openAiAnswer)).isNull();
    }

    @Test
    @DisplayName("폴백은 1순위가 받은 것과 같은 프롬프트(옵션 포함)를 받는다")
    void 같은_프롬프트() {
      primary.fail(failure(LlmFailureKind.UNAVAILABLE, 0, "Read timed out"));

      model.call(QUESTION);

      assertThat(primary.prompts).containsExactly(QUESTION);
      assertThat(fallback.prompts.get(0)).isSameAs(QUESTION);
    }
  }

  @Nested
  @DisplayName("턴 고정(STICKY) — 도구 루프 중간에 공급자를 왕복하지 않는다")
  class Sticky {

    @Test
    @DisplayName("이 턴(마지막 사용자 메시지 이후)에 폴백 공급자가 만든 assistant 메시지가 있으면 1순위를 부르지 않는다 — 게이트도 건드리지 않는다")
    void 이_턴의_폴백_산출() {
      Prompt round2 = new Prompt(List.of(new SystemMessage("시스템"), new UserMessage("질문"),
          assistant(OPENAI, "", echoCall("call_1")), toolResult("call_1")));

      ChatResponse response = model.call(round2);

      assertThat(LlmResponses.fallbackReason(response)).isEqualTo(FallbackChatModel.REASON_STICKY);
      assertThat(primary.calls()).isZero();
      assertThat(fallback.prompts).containsExactly(round2);
      assertThat(gate.successes + gate.failures).isZero();
    }

    @Test
    @DisplayName("1순위가 이어가던 턴에 폴백이 한 번 끼면 그 뒤로는 폴백 — 앞 라운드가 1순위 산출이어도")
    void 중간에_넘어간_턴() {
      Prompt round3 = new Prompt(List.of(new UserMessage("질문"),
          assistant(CLAUDE, "", echoCall("toolu_1")), toolResult("toolu_1"),
          assistant(OPENAI, "", echoCall("call_2")), toolResult("call_2")));

      assertThat(LlmResponses.fallbackReason(model.call(round3))).isEqualTo(FallbackChatModel.REASON_STICKY);
      assertThat(primary.calls()).isZero();
    }

    @Test
    @DisplayName("이전 턴의 폴백 산출 메시지는 무시한다 — 새 질문은 다시 1순위부터")
    void 이전_턴은_무시() {
      Prompt nextTurn = new Prompt(List.of(new UserMessage("첫 질문"), assistant(OPENAI, "지난 답"), new UserMessage("다음 질문")));

      assertThat(model.call(nextTurn)).isSameAs(claudeAnswer);
      assertThat(fallback.calls()).isZero();
    }

    @Test
    @DisplayName("공급자 표시가 없는 히스토리(Slack 스레드 복원)와 1순위 자신의 산출은 고정 사유가 아니다")
    void 표시없는_히스토리와_일순위_산출은_무시() {
      Prompt slackThread = new Prompt(List.of(new SystemMessage("시스템"), assistant(null, "봇이 올린 스레드 루트 메시지")));
      Prompt ownRound2 = new Prompt(List.of(new UserMessage("질문"), assistant(CLAUDE, "", echoCall("toolu_1")), toolResult("toolu_1")));

      assertThat(model.call(slackThread)).isSameAs(claudeAnswer);
      assertThat(model.call(ownRound2)).isSameAs(claudeAnswer);
      assertThat(fallback.calls()).isZero();
    }

    @Test
    @DisplayName("사용자 메시지가 하나도 없으면 전체 메시지를 이 턴으로 본다")
    void 사용자메시지_없음() {
      Prompt noUser = new Prompt(List.of(new SystemMessage("시스템"), assistant(OPENAI, "", echoCall("call_1")), toolResult("call_1")));

      assertThat(LlmResponses.fallbackReason(model.call(noUser))).isEqualTo(FallbackChatModel.REASON_STICKY);
      assertThat(primary.calls()).isZero();
    }

    @Test
    @DisplayName("고정은 게이트 차단보다 먼저 본다 — 차단 중이어도 사유는 STICKY")
    void 고정이_차단보다_먼저() {
      gate.recordFailure(failure(LlmFailureKind.EXHAUSTED, 402, "credit"), PRIMARY_MODEL, null);
      Prompt round2 = new Prompt(List.of(new UserMessage("질문"), assistant(OPENAI, "", echoCall("call_1")), toolResult("call_1")));

      assertThat(LlmResponses.fallbackReason(model.call(round2))).isEqualTo(FallbackChatModel.REASON_STICKY);
    }
  }

  @Nested
  @DisplayName("옵션과 ChatClient 경로")
  class Options {

    /** AdvisorAiConfig.chatChatClient 와 같은 조립(5-인자 builder + 히스토리를 넘기는 ToolCallingAdvisor) */
    private ChatClient chatClient() {
      return ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
              ToolCallingAdvisor.builder().toolCallingManager(ToolCallingManager.builder().build()).conversationHistoryEnabled(true))
          .build();
    }

    @Test
    @DisplayName("getOptions() 는 값이 하나도 없는 LlmChatOptions 다 — ToolCallingChatOptions 가 아니면 ChatClient 가 .tools() 를 조용히 버린다")
    void 기본옵션_타입() {
      assertThat(model.getOptions()).isExactlyInstanceOf(LlmChatOptions.class).isInstanceOf(ToolCallingChatOptions.class);
      assertThat(model.getOptions().getModel()).as("model 을 넣으면 한 공급자의 모델 ID 가 다른 공급자 요청으로 샌다").isNull();
      assertThat(model.getOptions().getMaxTokens()).isNull();
      assertThat(model.getOptions().getTemperature()).isNull();
      assertThat(model.getOptions().getJsonSchema()).isNull();
    }

    @Test
    @DisplayName("진짜 ChatClient 경로: .options(jsonSchema) + .tools + .toolContext 가 래퍼를 지나 1순위 모델에 그대로 도착한다")
    void ChatClient_경로_일순위() {
      ChatClient.builder(model).build().prompt().user("q")
          .options(LlmChatOptions.builder().jsonSchema(LlmChatOptionsTest.SCHEMA))
          .tools(new Tools())
          .toolContext(Map.of("userId", "U1"))
          .call().chatResponse();

      assertThat(primary.prompts.get(0).getOptions()).isExactlyInstanceOf(LlmChatOptions.class);
      LlmChatOptions sent = (LlmChatOptions) primary.prompts.get(0).getOptions();
      assertThat(sent.getJsonSchema()).isEqualTo(LlmChatOptionsTest.SCHEMA);
      assertThat(sent.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name()).containsExactly("echo");
      assertThat(sent.getToolContext()).containsEntry("userId", "U1");
      assertThat(sent.getModel()).as("래퍼는 model 을 정하지 않는다 — 공급자 모델의 기본값 몫").isNull();
      assertThat(sent.getMaxTokens()).isNull();
    }

    @Test
    @DisplayName("진짜 ChatClient 경로: 폴백으로 넘어가도 스키마·도구가 같은 옵션으로 폴백 모델에 도착하고, 사유는 ChatClient 가 돌려주는 응답까지 남는다")
    void ChatClient_경로_폴백() {
      primary.fail(failure(LlmFailureKind.EXHAUSTED, 402, "credit"));

      ChatResponse response = ChatClient.builder(model).build().prompt().user("q")
          .options(LlmChatOptions.builder().jsonSchema(LlmChatOptionsTest.SCHEMA))
          .tools(new Tools())
          .call().chatResponse();

      LlmChatOptions sent = (LlmChatOptions) fallback.prompts.get(0).getOptions();
      assertThat(sent.getJsonSchema()).isEqualTo(LlmChatOptionsTest.SCHEMA);
      assertThat(sent.getToolCallbacks()).extracting(callback -> callback.getToolDefinition().name()).containsExactly("echo");
      assertThat(sent).as("1순위가 받은 옵션 그대로").isSameAs(primary.prompts.get(0).getOptions());
      assertThat(LlmResponses.fallbackReason(response)).isEqualTo(LlmFailureKind.EXHAUSTED.name());
      assertThat(LlmResponses.provider(response)).isEqualTo(OPENAI);
    }

    @Test
    @DisplayName("진짜 도구 루프: 1라운드에 폴백이 이어받으면(거부) 어드바이저가 붙인 히스토리로 2라운드가 고정된다 — 1순위는 한 번만 불린다")
    void ChatClient_도구루프_고정() {
      primary.answer(claudeRefusal);
      AssistantMessage toolCallMessage = assistant(OPENAI, "", echoCall("call_1"));
      fallback.behavior = prompt -> fallback.calls() == 1 ? response(OPENAI, LlmResponses.FINISH_TOOL_CALLS, toolCallMessage) : openAiAnswer;
      Tools tools = new Tools();

      ChatResponse response = chatClient().prompt().system("시스템").user("q").tools(tools).call().chatResponse();

      assertThat(tools.invocations).as("폴백이 낸 도구 호출이 실행됐다").containsExactly("a");
      assertThat(primary.calls()).as("1라운드만 1순위").isEqualTo(1);
      assertThat(fallback.calls()).isEqualTo(2);
      assertThat(fallback.prompts.get(1).getInstructions()).as("어드바이저가 같은 AssistantMessage 인스턴스를 히스토리에 붙인다 — 고정 판정의 전제")
          .anySatisfy(message -> assertThat(message).isSameAs(toolCallMessage));
      assertThat(response.getResult().getOutput().getText()).isEqualTo("gpt 답");
      assertThat(LlmResponses.fallbackReason(response)).as("마지막 라운드의 사유").isEqualTo(FallbackChatModel.REASON_STICKY);
      assertThat(gate.isBlocked(PRIMARY_MODEL)).as("거부는 게이트를 차단하지 않는다").isFalse();
    }
  }
}
