package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국내업종 기간별시세(일/주/월/년) FHKUP03500100 응답. output2 는 최신 → 과거 순 최대 100건.
 */
public record KisIndexChartResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output1") Summary output1,
    @JsonProperty("output2") List<Candle> output2
) implements KisEnvelope {

  /** output1: 업종 요약 */
  public record Summary(
      @JsonProperty("hts_kor_isnm") String indexName,
      @JsonProperty("bstp_cls_code") String indexCode,
      @JsonProperty("bstp_nmix_prpr") String current,
      @JsonProperty("bstp_nmix_prdy_vrss") String prevDiff,
      @JsonProperty("bstp_nmix_prdy_ctrt") String changeRate
  ) {
  }

  /** output2: 지수 일봉 1건 */
  public record Candle(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("bstp_nmix_prpr") String close,
      @JsonProperty("bstp_nmix_oprc") String open,
      @JsonProperty("bstp_nmix_hgpr") String high,
      @JsonProperty("bstp_nmix_lwpr") String low,
      @JsonProperty("acml_vol") String volume,
      @JsonProperty("acml_tr_pbmn") String tradingValue,
      @JsonProperty("mod_yn") String modYn
  ) {
  }
}
