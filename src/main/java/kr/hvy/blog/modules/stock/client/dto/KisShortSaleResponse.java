package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국내주식 공매도 일별추이 FHPST04830000 응답. output2 가 일자별 공매도 체결 수량·대금·비중이다.
 */
public record KisShortSaleResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output2") List<Row> output2
) implements KisEnvelope {

  /** 일자 1건 */
  public record Row(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("ssts_cntg_qty") String shortSaleQty,
      @JsonProperty("ssts_tr_pbmn") String shortSaleAmt,
      @JsonProperty("ssts_vol_rlim") String shortSaleVolumeRatio
  ) {
  }
}
