package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 1순위 공급자 모델과 폴백 공급자 모델을 감싸 호출마다 어느 쪽으로 보낼지 정하는 {@link ChatModel}(2026-11-11). 역할(judge/assist/chat)마다 인스턴스를
 * 하나씩 두고 {@link LlmProviderGate} 하나를 공유한다. 호출부의 {@code ChatClient} 는 이 래퍼만 보므로 공급자가 바뀌어도 호출부는 그대로다.
 * <p>
 * <b>라우팅 규칙</b>({@link #call(Prompt)}, 위에서부터):
 * <ol>
 *   <li>둘 다 준비 안 됨 → {@link IllegalStateException}</li>
 *   <li>폴백만 준비됨 → 폴백 호출. 1순위를 끈 정상 경로라 폴백 사유를 싣지 않고 응답도 손대지 않는다</li>
 *   <li>1순위만 준비됨 → 1순위 호출. 넘길 곳이 없으므로 게이트 차단을 보지 않고, 실패와 거부 응답을 그대로 돌려준다(실패는 알림을 위해 게이트에 기록)</li>
 *   <li>둘 다 준비됨 → 이 턴을 이미 다른 공급자가 이어받았으면 폴백({@link #REASON_STICKY}), 게이트 차단 중이면 폴백({@link #REASON_BLOCKED_PREFIX}),
 *       아니면 1순위를 부르고 폴백 대상 실패({@link LlmFailureKind#name()})·거부({@link #REASON_REFUSAL})일 때 폴백</li>
 * </ol>
 * 폴백이 답한 응답에는 {@link LlmResponses#FALLBACK_REASON_METADATA_KEY} 로 사유를 싣는다. {@code AssistantMessage} 인스턴스는 건드리지 않는다 —
 * 도구 루프가 그 인스턴스를 히스토리에 붙이고, 다음 라운드의 STICKY 판정이 그 메타데이터({@link LlmResponses#PROVIDER_METADATA_KEY})를 읽는다.
 * <p>
 * <b>거부로 넘어간 응답의 사용량.</b> 거부는 HTTP 200 이라 1순위도 과금한다. 그 응답을 버리면 토큰이 기록에서 사라지므로 폴백 응답의 usage 에
 * 거부된 응답의 usage 를 더해 돌려준다({@link LlmResponses#withAddedUsage}). 예외로 넘어간 경우는 응답(과금)이 없어 더할 것이 없다.
 * <p>
 * <b>준비 여부는 호출마다 평가한다</b> — 키는 기동 뒤에도 채워질 수 있고(환경 갱신), 롤백 스위치({@code advisor.claude.enabled})도 같은 경로로 읽힌다.
 * <p>
 * 스트리밍은 구현하지 않는다(이 모듈은 비스트리밍만 쓴다 — 인터페이스 기본값이 {@link UnsupportedOperationException}).
 * 공급자 구현 클래스({@code client.openai}·{@code client.claude})에는 의존하지 않는다: 공급자 식별은 {@link LlmResponses} 의 메타데이터 계약으로만 한다.
 */
@Slf4j
public class FallbackChatModel implements ChatModel {

  /** 폴백 사유: 이 턴(마지막 사용자 메시지 이후)을 이미 1순위가 아닌 공급자가 이어받았다 — 도구 루프 중간에 공급자를 왕복하지 않는다 */
  public static final String REASON_STICKY = "STICKY";
  /** 폴백 사유 접두사: 게이트 차단 중이라 1순위를 부르지 않았다. 뒤에 차단 분류가 붙는다({@code BLOCKED:EXHAUSTED}) — {@link #blockedReason} */
  public static final String REASON_BLOCKED_PREFIX = "BLOCKED:";
  /** 폴백 사유: 1순위가 응답을 거부했다(공급자 장애가 아니라 요청 내용에 따른 것이라 게이트는 차단하지 않는다) */
  public static final String REASON_REFUSAL = "REFUSAL";

  private final LlmProvider primaryProvider;
  /** 1순위 모델 ID — 게이트의 모델 단위 차단(모델 없음 404) 키. null 이면 모델 단위 차단 없이 계정 단위로만 본다 */
  private final String primaryModel;
  /** 이 래퍼의 응답 없음(status 0 — 타임아웃·연결 실패·응답 해석 실패)을 한정할 게이트 범위 이름. null 이면 응답 없음도 계정 단위로 차단한다 */
  private final String unansweredScope;
  private final ChatModel primary;
  private final BooleanSupplier primaryReady;
  private final ChatModel fallback;
  private final BooleanSupplier fallbackReady;
  private final LlmProviderGate gate;

  /**
   * @param primaryProvider 1순위 공급자 식별 — 히스토리의 메시지가 1순위 산출인지 가리는 기준
   * @param primaryModel    1순위 모델 ID. 모델 없음(404)을 이 모델만의 차단으로 기록하는 데 쓴다 — null·공백이면 404 도 계정 단위로 차단된다
   * @param primary         1순위 공급자 모델. 실패는 {@link LlmProviderException}(분류 포함)으로 던져야 폴백된다
   * @param primaryReady    1순위를 쓸 수 있는지(켜짐·키·모델 ID). 호출마다 평가한다
   * @param fallback        폴백 공급자 모델
   * @param fallbackReady   폴백을 쓸 수 있는지. 호출마다 평가한다
   * @param gate            1순위의 차단 상태 — 역할별 래퍼가 한 인스턴스를 공유한다
   */
  public FallbackChatModel(LlmProvider primaryProvider, String primaryModel, ChatModel primary, BooleanSupplier primaryReady,
      ChatModel fallback, BooleanSupplier fallbackReady, LlmProviderGate gate) {
    this(primaryProvider, primaryModel, null, primary, primaryReady, fallback, fallbackReady, gate);
  }

  /**
   * 응답 타임아웃이 다른 역할보다 짧은 래퍼(채팅)용 생성자. 이 래퍼가 겪은 응답 없음은 {@code unansweredScope} 범위로만 차단한다 —
   * "짧은 타임아웃 안에 답이 없었다" 는 더 오래 기다리는 역할에 대한 증거가 아니다. 서버가 답한 실패(소진·키 오류·429·5xx)는 그대로 계정 단위다.
   *
   * @param unansweredScope 게이트 범위 이름(알림 문구에 그대로 실린다). 나머지 인자는 7-인자 생성자와 같다
   */
  public FallbackChatModel(LlmProvider primaryProvider, String primaryModel, String unansweredScope, ChatModel primary,
      BooleanSupplier primaryReady, ChatModel fallback, BooleanSupplier fallbackReady, LlmProviderGate gate) {
    this.primaryProvider = Objects.requireNonNull(primaryProvider, "primaryProvider");
    this.primaryModel = primaryModel;
    this.unansweredScope = unansweredScope;
    this.primary = Objects.requireNonNull(primary, "primary");
    this.primaryReady = Objects.requireNonNull(primaryReady, "primaryReady");
    this.fallback = Objects.requireNonNull(fallback, "fallback");
    this.fallbackReady = Objects.requireNonNull(fallbackReady, "fallbackReady");
    this.gate = Objects.requireNonNull(gate, "gate");
  }

  /**
   * 게이트 차단으로 폴백했을 때의 사유 문자열({@code BLOCKED:<분류>}).
   */
  public static String blockedReason(LlmFailureKind kind) {
    return REASON_BLOCKED_PREFIX + kind.name();
  }

  /**
   * 래퍼의 기본 옵션 — <b>값이 하나도 없는</b> {@link LlmChatOptions}. 반드시 오버라이드해야 하는 이유가 둘이다.
   * (1) 인터페이스 기본값은 {@code ToolCallingChatOptions} 가 아니라서 ChatClient 가 {@code .tools()} 를 조용히 버린다(예외 없음).
   * (2) ChatClient 는 이 옵션을 base 로 요청 옵션을 병합하므로 여기에 model·maxTokens 를 넣으면 한 공급자의 값이 다른 공급자 요청으로 샌다 —
   * 그 둘은 공급자 모델의 기본 옵션이 소유한다.
   */
  @Override
  public LlmChatOptions getOptions() {
    return LlmChatOptions.builder().build();
  }

  /**
   * 준비 상태에 따라 경로를 고른다(클래스 javadoc 의 규칙 1~4). 준비 여부는 여기서 호출마다 한 번씩만 읽는다.
   *
   * @throws IllegalStateException 두 공급자 모두 준비되지 않았을 때
   */
  @Override
  public ChatResponse call(Prompt prompt) {
    boolean primaryUp = primaryReady.getAsBoolean();
    boolean fallbackUp = fallbackReady.getAsBoolean();
    if (!primaryUp && !fallbackUp) {
      throw new IllegalStateException("LLM 공급자가 하나도 준비되지 않았습니다 — 1순위(" + primaryProvider
          + ")와 폴백 공급자의 API 키·모델 ID 설정을 확인하세요(spring.ai.anthropic.api-key·advisor.claude.*, spring.ai.openai.api-key·advisor.model.*)");
    }
    if (!primaryUp) {
      return fallback.call(prompt);
    }
    if (!fallbackUp) {
      return callPrimaryOnly(prompt);
    }
    return callPrimaryOrFallback(prompt);
  }

  /**
   * 폴백이 없을 때의 1순위 호출. 넘길 곳이 없으니 실패·거부를 그대로 돌려주되, 게이트에는 기록한다 — 실패는 알림이 나가게, 성공은 차단 기록이 지워지게
   * (게이트를 공유하는 다른 역할은 폴백이 준비돼 있을 수 있어, 여기서 본 성공·실패가 그쪽 라우팅에 쓰인다).
   */
  private ChatResponse callPrimaryOnly(Prompt prompt) {
    long callToken = gate.beginCall();
    ChatResponse response;
    try {
      response = primary.call(prompt);
    } catch (LlmProviderException e) {
      gate.recordFailure(e, primaryModel, unansweredScope);
      throw e;
    }
    if (!isRefusal(response)) {
      gate.recordSuccess(callToken, primaryModel, unansweredScope);
    }
    return response;
  }

  /**
   * 둘 다 준비됐을 때의 라우팅. 고정(STICKY) → 차단(BLOCKED) → 1순위 호출 순으로 본다.
   * <p>
   * 1순위가 던진 것 중 폴백하는 것은 폴백 대상 분류의 {@link LlmProviderException} 뿐이다. 요청 오류(REQUEST_INVALID)와 그 밖의 예외는 그대로 던진다 —
   * 다른 공급자가 대신 답하면 요청 조립 버그가 가려진다. 거부 응답은 게이트에 성공으로도 실패로도 기록하지 않는다(공급자 상태와 무관).
   * 성공은 호출 직전에 받은 순번과 함께 기록한다 — 호출이 도는 동안 다른 역할이 기록한 차단을 이 성공이 지우지 않게.
   */
  private ChatResponse callPrimaryOrFallback(Prompt prompt) {
    if (continuedByOtherProvider(prompt.getInstructions())) {
      return callFallback(prompt, REASON_STICKY, "이 턴을 이미 폴백 공급자가 이어받음", null);
    }
    Optional<LlmProviderGate.Block> block = gate.currentBlock(primaryModel, unansweredScope);
    if (block.isPresent()) {
      return callFallback(prompt, blockedReason(block.get().kind()), blockedDetail(block.get()), null);
    }
    long callToken = gate.beginCall();
    ChatResponse response;
    try {
      response = primary.call(prompt);
    } catch (LlmProviderException e) {
      if (!e.getKind().isFallbackable()) {
        throw e;
      }
      gate.recordFailure(e, primaryModel, unansweredScope);
      return callFallback(prompt, e.getKind().name(), e.getMessage(), e);
    }
    if (isRefusal(response)) {
      // 거부된 응답도 과금됐다 — 폴백 응답의 usage 에 더해 토큰이 기록에서 빠지지 않게 한다
      return LlmResponses.withAddedUsage(callFallback(prompt, REASON_REFUSAL, "1순위가 응답을 거부", null), response);
    }
    gate.recordSuccess(callToken, primaryModel, unansweredScope);
    return response;
  }

  /**
   * 차단 중 폴백의 로그용 설명 — 만료 시각·범위(계정 단위가 아니면 범위 키)·원인.
   */
  private static String blockedDetail(LlmProviderGate.Block block) {
    return "차단 중(" + block.until() + " 까지" + (block.scoped() ? ", 범위 " + block.scope() : "") + "): " + block.reason();
  }

  /**
   * 폴백 공급자를 부르고 응답에 사유를 싣는다. 폴백도 던지면 그 예외가 나가되, 1순위 예외가 있었으면 suppressed 로 붙여 둘 다 실패했음을 남긴다.
   *
   * @param detail         로그용 1순위 오류 요약
   * @param primaryFailure 이 호출에서 1순위가 던진 예외. 1순위를 부르지 않았거나(STICKY·BLOCKED) 거부였으면 null
   */
  private ChatResponse callFallback(Prompt prompt, String reason, String detail, LlmProviderException primaryFailure) {
    if (REASON_STICKY.equals(reason)) {
      // 이미 넘어간 턴의 이어지는 라운드 — 새 사건이 아니라 라운드마다 warn 을 남기지 않는다(넘어간 시점의 warn 이 원인을 갖고 있다)
      log.info("LLM 폴백 유지: 1순위 {} 대신 폴백 공급자 — 사유={}, {}", primaryProvider, reason, detail);
    } else {
      log.warn("LLM 폴백: 1순위 {} 대신 폴백 공급자로 넘긴다 — 사유={}, {}", primaryProvider, reason, detail);
    }
    ChatResponse response;
    try {
      response = fallback.call(prompt);
    } catch (RuntimeException e) {
      if (primaryFailure != null && primaryFailure != e) {
        e.addSuppressed(primaryFailure);
      }
      throw e;
    }
    return LlmResponses.withMetadata(response, LlmResponses.FALLBACK_REASON_METADATA_KEY, reason);
  }

  /**
   * 이 턴에 1순위가 아닌 공급자가 만든 assistant 메시지가 있는지. 턴은 마지막 사용자 메시지 이후이고, 사용자 메시지가 하나도 없으면 전체다.
   * 공급자 표시가 없는 메시지(Slack 스레드에서 복원한 히스토리)는 누가 만들었는지 알 수 없으므로 세지 않는다.
   * <p>
   * 도구 루프 중간에 공급자를 바꾸지 않는 이유: 폴백 공급자가 낸 도구 호출과 그 결과가 히스토리에 쌓인 뒤 1순위로 돌아가면, 1순위는 자기가 내지 않은
   * 도구 호출(추론 블록 없음, 다른 id 체계)을 이어받아야 한다. 한 번 넘어간 턴은 끝까지 폴백이 맡는다.
   */
  private boolean continuedByOtherProvider(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      Message message = messages.get(i);
      if (message.getMessageType() == MessageType.USER) {
        return false;
      }
      if (message.getMessageType() == MessageType.ASSISTANT) {
        LlmProvider producer = LlmResponses.provider(message);
        if (producer != null && producer != primaryProvider) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * 거부 응답인지. 거부는 HTTP 200 이라 예외가 아니라 종료 사유({@link LlmResponses#FINISH_REFUSAL})로 온다.
   */
  private static boolean isRefusal(ChatResponse response) {
    return LlmResponses.FINISH_REFUSAL.equals(LlmResponses.finishReason(response));
  }
}
