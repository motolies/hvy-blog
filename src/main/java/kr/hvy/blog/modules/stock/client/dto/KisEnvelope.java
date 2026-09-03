package kr.hvy.blog.modules.stock.client.dto;

/**
 * KIS 응답 공통 봉투. HTTP 200 이어도 rt_cd 가 "0" 이 아니면 실패다.
 */
public interface KisEnvelope {

  String rtCd();

  String msgCd();

  String msg1();

  /**
   * 업무 성공 여부(rt_cd == "0").
   */
  default boolean isSuccess() {
    return "0".equals(rtCd());
  }
}
