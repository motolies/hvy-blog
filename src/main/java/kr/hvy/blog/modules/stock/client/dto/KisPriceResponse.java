package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 주식현재가 시세 FHKST01010100 응답. 밸류에이션 스냅샷(시총·PER·PBR·52주·외인 소진율)에 쓴다.
 */
public record KisPriceResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") Output output
) implements KisEnvelope {

  /** output: 현재가 스냅샷 */
  public record Output(
      @JsonProperty("stck_shrn_iscd") String ticker,
      @JsonProperty("stck_prpr") String currentPrice,
      @JsonProperty("hts_avls") String marketCapHundredMillion,
      @JsonProperty("lstn_stcn") String listedShares,
      @JsonProperty("per") String per,
      @JsonProperty("pbr") String pbr,
      @JsonProperty("eps") String eps,
      @JsonProperty("bps") String bps,
      @JsonProperty("w52_hgpr") String week52High,
      @JsonProperty("w52_lwpr") String week52Low,
      @JsonProperty("hts_frgn_ehrt") String foreignHoldRate,
      @JsonProperty("frgn_hldn_qty") String foreignHoldQty,
      @JsonProperty("stac_month") String settleMonth,
      @JsonProperty("temp_stop_yn") String tempStop,
      @JsonProperty("sltr_yn") String liquidating,
      @JsonProperty("mang_issu_cls_code") String administrative,
      @JsonProperty("mrkt_warn_cls_code") String marketWarning
  ) {
  }
}
