package kr.hvy.blog.modules.advisor.client.claude;

import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorType;
import java.util.List;
import kr.hvy.blog.modules.advisor.client.llm.LlmFailureKind;
import kr.hvy.blog.modules.advisor.client.llm.LlmProvider;
import kr.hvy.blog.modules.advisor.client.llm.LlmProviderException;
import org.apache.commons.lang3.StringUtils;

/**
 * Claude 호출 실패(SDK 예외) → {@link LlmProviderException} 분류(2026-11-11). 폴백 여부와 차단 시간이 이 분류로 정해진다.
 * <p>
 * 판정은 예외 클래스가 아니라 <b>상태 코드 + 오류 본문</b>({@code statusCode()}·{@code errorType()}·{@code apiMessage()}·{@code errorCode()})으로 한다 —
 * SDK 는 402 에 전용 클래스가 없고({@code UnexpectedStatusCodeException}), 지출 한도는 400·429 안에 섞여 있어 클래스만으로는 가를 수 없다.
 * <pre>
 * EXHAUSTED        error.type=billing_error(상태 무관) · 402 · 400 지출 한도 문구 · 429 + details.error_code=enforced_spend_limit_reached
 * MISCONFIGURED    401 · 403 · 404
 * UNAVAILABLE      그 외 429 · 408 · 409 · 5xx(529 포함) · 응답을 받지 못했거나 해석하지 못한 경우(status 0)
 * REQUEST_INVALID  그 외 4xx(400 · 413 · 422 …) · SDK 가 던지지 않은 로컬 예외(status 0)
 * </pre>
 * <b>SDK 예외가 아닌 것은 일시 장애로 보지 않는다.</b> 전송 실패는 {@code RestClientHttpClient} 가 {@link AnthropicIoException} 으로 바꿔 던지므로,
 * 그 밖의 예외(직렬화·어댑터 버그 등)는 Claude 의 상태와 무관하게 매번 같은 자리에서 난다. 일시 장애로 분류하면 폴백이 답을 내 버그가 가려지고
 * 게이트가 "Claude 장애" 알림을 보낸다 — 요청 오류로 분류해 폴백 없이 실패시킨다.
 * 메시지에는 API 오류 문구만 줄여 싣는다 — 요청 헤더·키는 다루지 않는다.
 */
final class ClaudeErrors {

  /** Console 지출 한도(조직·워크스페이스)에 닿았을 때의 400 문구 머리말. 기다려도 풀리지 않는다 */
  static final String SPEND_LIMIT_MESSAGE_PREFIX = "You have reached your specified";
  /** 방어적 — 현행 공식 문서에는 없는 과거 형태의 크레딧 소진 400 문구. 다시 나타나면 요청 오류로 굳지 않고 폴백되게 한다 */
  static final String LEGACY_CREDIT_MESSAGE = "credit balance is too low";
  /** 429 중 기다려도 풀리지 않는 지출 한도 강제({@code error.details.error_code}) */
  static final String ENFORCED_SPEND_LIMIT_CODE = "enforced_spend_limit_reached";
  /** 결제·크레딧 문제를 뜻하는 오류 유형({@code error.type}) — 상태 코드와 무관하게 소진으로 본다 */
  static final String BILLING_ERROR_TYPE = ErrorType.BILLING_ERROR.asString();

  private static final String RETRY_AFTER_HEADER = "retry-after";
  private static final int MESSAGE_ABBREVIATION = 300;

  private ClaudeErrors() {
  }

  /**
   * SDK 호출에서 나온 예외를 분류한다. HTTP 응답을 받은 실패는 상태 코드·Retry-After 를 싣고, 받지 못한 실패는 status 0 이다.
   */
  static LlmProviderException classify(Exception error) {
    if (error instanceof LlmProviderException classified) {
      return classified;
    }
    if (error instanceof AnthropicServiceException service) {
      int status = service.statusCode();
      String apiMessage = service.apiMessage().orElse(null);
      // 유형은 원시 JSON 값으로 읽는다 — ErrorType.asString() 은 값이 문자열이 아니면 던진다
      String errorType = service.errorType().flatMap(type -> type._value().asString()).orElse(null);
      LlmFailureKind kind = kindOf(status, apiMessage, service.errorCode().orElse(null), errorType);
      return new LlmProviderException(LlmProvider.CLAUDE, kind, status, retryAfterSeconds(service.headers()),
          serviceMessage(service, apiMessage), service);
    }
    if (error instanceof AnthropicIoException) {
      return unanswered("응답 없음(타임아웃·연결 실패): " + describe(error), error);
    }
    if (error instanceof AnthropicInvalidDataException) {
      return unanswered("응답 해석 실패: " + describe(error), error);
    }
    if (error instanceof AnthropicException) {
      return unanswered("호출 실패: " + describe(error), error);
    }
    return new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.REQUEST_INVALID, 0, null,
        "호출 전 로컬 오류(Claude 응답 아님): " + describe(error), error);
  }

  /**
   * 상태 코드와 오류 본문으로 분류한다. 소진 판정이 먼저다 — 같은 400·429 안에서 "기다리면 풀리는가"를 갈라야 하기 때문이다.
   * 4xx 가 아닌 비정상 상태(3xx 등)는 요청 잘못이 아니므로 일시 장애로 본다.
   */
  static LlmFailureKind kindOf(int status, String apiMessage, String errorCode, String errorType) {
    if (isExhausted(status, apiMessage, errorCode, errorType)) {
      return LlmFailureKind.EXHAUSTED;
    }
    if (status == 401 || status == 403 || status == 404) {
      return LlmFailureKind.MISCONFIGURED;
    }
    if (status == 429 || status == 408 || status == 409 || status < 400 || status >= 500) {
      return LlmFailureKind.UNAVAILABLE;
    }
    return LlmFailureKind.REQUEST_INVALID;
  }

  /**
   * 기다려도 풀리지 않는 소진인가. 오류 유형이 {@code billing_error} 면 상태 코드를 보지 않는다 — 유형은 API 가 직접 붙인 분류라 문구보다 안정적이다.
   * 유형이 없거나 다른 값이면 상태별 규칙(402 · 400 의 지출 한도 문구 · 429 의 지출 한도 강제 코드)으로 본다.
   */
  private static boolean isExhausted(int status, String apiMessage, String errorCode, String errorType) {
    if (BILLING_ERROR_TYPE.equals(errorType)) {
      return true;
    }
    return switch (status) {
      case 402 -> true;
      case 400 -> StringUtils.startsWith(apiMessage, SPEND_LIMIT_MESSAGE_PREFIX)
          || StringUtils.containsIgnoreCase(apiMessage, LEGACY_CREDIT_MESSAGE);
      case 429 -> ENFORCED_SPEND_LIMIT_CODE.equals(errorCode);
      default -> false;
    };
  }

  /**
   * Retry-After(초). 없거나 숫자가 아니면(HTTP-date 형식 등) null → 기본 백오프.
   */
  static Long retryAfterSeconds(Headers headers) {
    List<String> values = headers.values(RETRY_AFTER_HEADER);
    if (values.isEmpty() || StringUtils.isBlank(values.get(0))) {
      return null;
    }
    try {
      return Math.max(0L, Long.parseLong(values.get(0).trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * HTTP 응답을 받지 못했거나 해석하지 못한 실패 — 일시 장애, status 0(재시도하지 않고 바로 폴백한다).
   */
  private static LlmProviderException unanswered(String message, Exception cause) {
    return new LlmProviderException(LlmProvider.CLAUDE, LlmFailureKind.UNAVAILABLE, 0, null, message, cause);
  }

  /**
   * HTTP 오류 응답의 설명. API 오류 문구({@code error.message})가 있으면 그것, 없으면(게이트웨이의 HTML 오류 등) 본문 머리를 싣는다.
   */
  private static String serviceMessage(AnthropicServiceException service, String apiMessage) {
    if (StringUtils.isNotBlank(apiMessage)) {
      return abbreviate(apiMessage);
    }
    String body = String.valueOf(service.body());
    return StringUtils.isBlank(body) ? "오류 본문 없음" : abbreviate(body);
  }

  /**
   * 예외 설명 한 줄(클래스명 + 메시지, 상한 300자).
   */
  private static String describe(Exception error) {
    String message = error.getMessage();
    return abbreviate(StringUtils.isBlank(message) ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + message);
  }

  /**
   * 공백을 한 칸으로 줄이고 300자에서 자른다 — 오류 본문이 통째로 예외 메시지·알림에 붙지 않게.
   */
  private static String abbreviate(String text) {
    return StringUtils.abbreviate(StringUtils.normalizeSpace(text), MESSAGE_ABBREVIATION);
  }
}
