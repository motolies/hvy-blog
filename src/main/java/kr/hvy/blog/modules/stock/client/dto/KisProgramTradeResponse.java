package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 종목별 프로그램매매추이(일별) FHPPG04650201 응답.
 */
public record KisProgramTradeResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") List<Row> output
) implements KisEnvelope {

  /** 일자 1건 */
  public record Row(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("whol_smtn_ntby_qty") String netQty,
      @JsonProperty("whol_smtn_ntby_tr_pbmn") String netAmt
  ) {
  }
}
