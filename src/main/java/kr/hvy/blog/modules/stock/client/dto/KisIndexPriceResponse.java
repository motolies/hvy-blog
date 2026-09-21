package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 국내업종 현재지수 FHPUP02100000 응답 (inquire-index-price, FID_COND_MRKT_DIV_CODE=U). advisor 장중 점검이 KOSPI(0001)·KOSDAQ(1001) 현재 등락률에 쓴다.
 * 필드명은 KIS 저장소 data.csv column_mapping 기준(bstp_nmix_prpr 업종 지수 현재가, bstp_nmix_prdy_ctrt 전일 대비율). TR ID 는 KisIndexPriceManualTest 로 실측한다.
 * 지수 시가(bstp_nmix_oprc, data.csv inquire_index_price 컬럼)는 12:00 픽 노트의 "시가 기준 지수 대비 초과" 용으로 2026-09-21 에 맨 뒤에 더했다(위치 record).
 */
public record KisIndexPriceResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") Output output
) implements KisEnvelope {

  /** output: 지수 현재가 */
  public record Output(
      @JsonProperty("bstp_nmix_prpr") String currentValue,
      @JsonProperty("bstp_nmix_prdy_vrss") String prevDiff,
      @JsonProperty("prdy_vrss_sign") String prevDiffSign,
      @JsonProperty("bstp_nmix_prdy_ctrt") String changeRate,
      @JsonProperty("acml_vol") String accumulatedVolume,
      @JsonProperty("acml_tr_pbmn") String accumulatedTradingValue,
      @JsonProperty("bstp_nmix_oprc") String openValue
  ) {
  }
}
