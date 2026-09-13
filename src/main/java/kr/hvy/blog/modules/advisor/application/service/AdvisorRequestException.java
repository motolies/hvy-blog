package kr.hvy.blog.modules.advisor.application.service;

/**
 * 잘못된 트리거 요청(미등록 잡·설정 누락·날짜 오류). 관리자 API 는 400 으로 돌려준다.
 */
public class AdvisorRequestException extends RuntimeException {

  public AdvisorRequestException(String message) {
    super(message);
  }
}
