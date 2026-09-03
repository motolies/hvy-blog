package kr.hvy.blog.modules.stock.client;

import lombok.Getter;

/**
 * KIS API 호출 최종 실패. 재시도 가능 여부와 KIS 응답 코드를 함께 전달한다.
 */
@Getter
public class KisApiException extends RuntimeException {

  private final String trId;
  private final Integer httpStatus;
  private final String rtCd;
  private final String msgCd;
  private final boolean retryable;

  public KisApiException(String trId, Integer httpStatus, String rtCd, String msgCd, boolean retryable, String message, Throwable cause) {
    super(message, cause);
    this.trId = trId;
    this.httpStatus = httpStatus;
    this.rtCd = rtCd;
    this.msgCd = msgCd;
    this.retryable = retryable;
  }
}
