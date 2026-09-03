package kr.hvy.blog.modules.stock.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import kr.hvy.blog.modules.stock.client.dto.KisEnvelope;
import kr.hvy.blog.modules.stock.client.dto.KisEnvelopeOnly;
import kr.hvy.blog.modules.stock.domain.code.KisErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * KIS REST 저수준 클라이언트. 레이트 리미트·토큰·재시도·rt_cd 검증을 한곳에서 처리한다.
 * <p>
 * {@code RestClientConfigurer} 가 HttpClient 자동 재시도를 꺼 두었으므로 재시도는 여기서 한다.
 * {@code RestApi} 파사드는 non-2xx 마다 Slack 을 쏘므로 쓰지 않고 {@code exchange} 로 상태·본문을 직접 다룬다.
 * KIS 는 HTTP 200 으로 rt_cd 실패를 주는 경우가 있어 상태코드만 보면 빈 데이터가 조용히 적재된다 — 봉투 검사를 강제한다.
 */
@Slf4j
@Component
public class KisApiClient {

  private static final int MAX_ATTEMPTS = 5;
  private static final long RATE_LIMIT_PAUSE_MILLIS = 1_000L;
  private static final long BASE_BACKOFF_MILLIS = 500L;
  private static final long MAX_BACKOFF_MILLIS = 30_000L;
  private static final long JITTER_MILLIS = 250L;

  private final RestClient kisRestClient;
  private final KisProperties properties;
  private final KisTokenManager tokenManager;
  private final KisRateLimiter rateLimiter;
  private final KisApiFailureRecorder failureRecorder;

  public KisApiClient(@Qualifier("kisRestClient") RestClient kisRestClient, KisProperties properties,
      KisTokenManager tokenManager, KisRateLimiter rateLimiter, KisApiFailureRecorder failureRecorder) {
    this.kisRestClient = kisRestClient;
    this.properties = properties;
    this.tokenManager = tokenManager;
    this.rateLimiter = rateLimiter;
    this.failureRecorder = failureRecorder;
  }

  /**
   * 단건 GET 호출 (연속조회 아님).
   */
  public <T extends KisEnvelope> KisResponse<T> get(String path, String trId, Map<String, String> params,
      Class<T> responseType, KisCallContext context) {
    return get(path, trId, params, null, responseType, context);
  }

  /**
   * GET 호출. 재시도 정책:
   * <ul>
   *   <li>EGW00201(초당 한도) → 리미터 하향 후 1초 대기, 최대 {@value #MAX_ATTEMPTS}회</li>
   *   <li>5xx / 네트워크 오류 → 지수 백오프(0.5s→…, 상한 30s) + 지터</li>
   *   <li>401 / 토큰 무효 코드 → 강제 재발급 후 1회만 재시도</li>
   *   <li>rt_cd != "0" 업무 오류, 기타 4xx → 즉시 실패</li>
   * </ul>
   *
   * @param trCont 연속조회 요청 헤더 (최초 공백, 다음 페이지 N)
   */
  public <T extends KisEnvelope> KisResponse<T> get(String path, String trId, Map<String, String> params,
      String trCont, Class<T> responseType, KisCallContext context) {
    boolean tokenRefreshed = false;
    RawResponse last = null;

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      rateLimiter.acquire();
      String token = tokenManager.getAccessToken();

      RawResponse raw;
      try {
        raw = exchange(path, trId, params, trCont, token);
      } catch (ResourceAccessException e) {
        context.stats().getApiCalls().incrementAndGet();
        log.warn("KIS 호출 네트워크 오류 (시도 {}/{}): trId={}, target={}, cause={}",
            attempt, MAX_ATTEMPTS, trId, context.targetKey(), e.getMessage());
        if (attempt == MAX_ATTEMPTS) {
          throw fail(context, trId, params, null, attempt, true, "네트워크 오류: " + e.getMessage(), e);
        }
        context.stats().getRetries().incrementAndGet();
        backoff(attempt);
        continue;
      }

      context.stats().getApiCalls().incrementAndGet();
      last = raw;
      Outcome outcome = classify(raw);

      switch (outcome) {
        case SUCCESS -> {
          rateLimiter.onSuccess();
          return new KisResponse<>(parse(raw, responseType, trId, params, context, attempt), raw.trCont(), raw.status());
        }
        case RATE_LIMITED -> {
          context.stats().getRateLimitHits().incrementAndGet();
          rateLimiter.onRateLimited();
          if (attempt == MAX_ATTEMPTS) {
            throw fail(context, trId, params, raw, attempt, true, "초당 호출 한도 초과 재시도 소진", null);
          }
          pause(RATE_LIMIT_PAUSE_MILLIS + ThreadLocalRandom.current().nextLong(JITTER_MILLIS * 2));
        }
        case TOKEN_INVALID -> {
          if (tokenRefreshed) {
            throw fail(context, trId, params, raw, attempt, false, "토큰 재발급 후에도 인증 실패", null);
          }
          log.warn("KIS 토큰 무효 응답 → 강제 재발급: trId={}, status={}, msgCd={}", trId, raw.status(), raw.msgCd());
          tokenManager.forceRefresh();
          tokenRefreshed = true;
        }
        case SERVER_ERROR -> {
          if (attempt == MAX_ATTEMPTS) {
            throw fail(context, trId, params, raw, attempt, true, "KIS 서버 오류 재시도 소진", null);
          }
          backoff(attempt);
        }
        case BUSINESS_ERROR, CLIENT_ERROR -> throw fail(context, trId, params, raw, attempt, false,
            "KIS 업무 오류: " + raw.msgCd() + " " + raw.msg1(), null);
      }
      context.stats().getRetries().incrementAndGet();
    }

    throw fail(context, trId, params, last, MAX_ATTEMPTS, true, "재시도 소진", null);
  }

  /**
   * 실제 HTTP 1회. 상태·본문·tr_cont 헤더를 그대로 돌려준다.
   */
  private RawResponse exchange(String path, String trId, Map<String, String> params, String trCont, String token) {
    return kisRestClient.get()
        .uri(builder -> buildUri(builder, path, params))
        .header("authorization", "Bearer " + token)
        .header("appkey", properties.getAppKey())
        .header("appsecret", properties.getAppSecret())
        .header("tr_id", trId)
        .header("custtype", properties.getCustType())
        .header("tr_cont", trCont == null ? "" : trCont)
        .exchange((request, response) -> {
          String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
          KisEnvelopeOnly envelope = KisJson.tryRead(body, KisEnvelopeOnly.class);
          return new RawResponse(response.getStatusCode().value(), body,
              response.getHeaders().getFirst("tr_cont"),
              envelope == null ? null : envelope.rtCd(),
              envelope == null ? null : envelope.msgCd(),
              envelope == null ? null : envelope.msg1());
        });
  }

  private URI buildUri(UriBuilder builder, String path, Map<String, String> params) {
    builder.path(path);
    if (params != null) {
      params.forEach((key, value) -> {
        if (value != null) {
          builder.queryParam(key, value);
        }
      });
    }
    return builder.build();
  }

  /**
   * 응답을 재시도 정책 분기로 분류한다. 상태코드보다 msg_cd 를 먼저 본다(EGW00201 은 5xx 로 올 수 있다).
   */
  private Outcome classify(RawResponse raw) {
    if (KisErrorCode.isRateLimited(raw.msgCd())) {
      return Outcome.RATE_LIMITED;
    }
    if (raw.status() == HttpStatus.UNAUTHORIZED.value() || KisErrorCode.isTokenInvalid(raw.msgCd())) {
      return Outcome.TOKEN_INVALID;
    }
    if (raw.status() >= 500) {
      return Outcome.SERVER_ERROR;
    }
    if (raw.status() >= 200 && raw.status() < 300) {
      return "0".equals(raw.rtCd()) ? Outcome.SUCCESS : Outcome.BUSINESS_ERROR;
    }
    return Outcome.CLIENT_ERROR;
  }

  private <T> T parse(RawResponse raw, Class<T> type, String trId, Map<String, String> params,
      KisCallContext context, int attempt) {
    try {
      return KisJson.read(raw.body(), type);
    } catch (RuntimeException e) {
      throw fail(context, trId, params, raw, attempt, false, "응답 파싱 실패: " + e.getMessage(), e);
    }
  }

  /**
   * 최종 실패를 기록하고 예외를 만든다. 호출부는 이 예외를 종목 단위로 잡아 실패 목록에 누적한다.
   */
  private KisApiException fail(KisCallContext context, String trId, Map<String, String> params, RawResponse raw,
      int attempt, boolean retryable, String reason, Throwable cause) {
    context.stats().getApiFails().incrementAndGet();
    Integer status = raw == null ? null : raw.status();
    String rtCd = raw == null ? null : raw.rtCd();
    String msgCd = raw == null ? null : raw.msgCd();
    failureRecorder.record(context, trId, String.valueOf(params), status, rtCd, msgCd,
        raw == null ? null : raw.body(), attempt);
    String message = String.format("[%s] %s (target=%s, status=%s, rt_cd=%s, msg_cd=%s)",
        trId, reason, context.targetKey(), status, rtCd, msgCd);
    return new KisApiException(trId, status, rtCd, msgCd, retryable, message, cause);
  }

  private void backoff(int attempt) {
    long base = Math.min(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)), MAX_BACKOFF_MILLIS);
    pause(base + ThreadLocalRandom.current().nextLong(JITTER_MILLIS));
  }

  private void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KisApiException(null, null, null, null, false, "KIS 재시도 대기 중 인터럽트", e);
    }
  }

  private enum Outcome { SUCCESS, RATE_LIMITED, TOKEN_INVALID, SERVER_ERROR, BUSINESS_ERROR, CLIENT_ERROR }

  /**
   * HTTP 응답 원본 + 봉투 요약.
   */
  private record RawResponse(int status, String body, String trCont, String rtCd, String msgCd, String msg1) {

    @Override
    public String toString() {
      return "status=" + status + ", rt_cd=" + rtCd + ", msg_cd=" + msgCd + ", body=" + StringUtils.abbreviate(body, 200);
    }
  }
}
