package kr.hvy.blog.modules.stock.client;

import kr.hvy.blog.modules.stock.client.dto.KisEnvelope;

/**
 * 파싱된 본문과 연속조회 헤더를 함께 돌려준다.
 *
 * @param body       파싱된 응답
 * @param trCont     응답 헤더 tr_cont: F/M 이면 다음 페이지 있음, D/E 면 마지막
 * @param httpStatus HTTP 상태코드
 */
public record KisResponse<T extends KisEnvelope>(T body, String trCont, int httpStatus) {

  /**
   * 연속조회 다음 페이지가 있는지 판정한다.
   */
  public boolean hasNext() {
    return "F".equals(trCont) || "M".equals(trCont);
  }
}
