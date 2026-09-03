package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 해외주식 종목/지수/환율 기간별시세 FHKST03030100 응답 (N 지수, X 환율).
 */
public record KisOverseasIndexChartResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output2") List<Candle> output2
) implements KisEnvelope {

  /** output2: 일봉 1건 */
  public record Candle(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("ovrs_nmix_prpr") String close,
      @JsonProperty("ovrs_nmix_oprc") String open,
      @JsonProperty("ovrs_nmix_hgpr") String high,
      @JsonProperty("ovrs_nmix_lwpr") String low,
      @JsonProperty("acml_vol") String volume,
      @JsonProperty("mod_yn") String modYn
  ) {
  }
}
