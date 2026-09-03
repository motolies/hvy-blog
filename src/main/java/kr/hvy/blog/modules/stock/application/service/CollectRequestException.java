package kr.hvy.blog.modules.stock.application.service;

/**
 * 잘못된 수집 요청(형식·전제조건 위반). 관리자 API 는 400 으로 응답하며, 조작 실수라 Slack 알림 대상이 아니다.
 */
public class CollectRequestException extends RuntimeException {

  public CollectRequestException(String message) {
    super(message);
  }
}
