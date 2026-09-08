package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * ETF/ETN NAV 비교추이(일) FHPST02440200 응답. output 하나에 일자별 종가·NAV·괴리율이 온다 (1회 최대 100건, 연속조회 없음).
 * 필드명은 KIS data.csv column_mapping 기준 (claudedocs/kis/data.csv).
 */
public record KisEtfNavResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") List<Row> output
) implements KisEnvelope {

  /** 일자 1건 */
  public record Row(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("stck_clpr") String close,
      @JsonProperty("prdy_vrss") String prevDiff,
      @JsonProperty("prdy_vrss_sign") String prevDiffSign,
      @JsonProperty("prdy_ctrt") String changeRate,
      @JsonProperty("acml_vol") String volume,
      @JsonProperty("cntg_vol") String contractVolume,
      @JsonProperty("dprt") String disparityRate,
      @JsonProperty("nav_vrss_prpr") String navDiff,
      @JsonProperty("nav") String nav,
      @JsonProperty("nav_prdy_vrss_sign") String navPrevDiffSign,
      @JsonProperty("nav_prdy_vrss") String navPrevDiff,
      @JsonProperty("nav_prdy_ctrt") String navChangeRate
  ) {
  }
}
