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

  /**
   * output: 현재가 스냅샷. 전일 대비·누적 거래량은 advisor 장중 점검이 쓴다(2026-09-13).
   * 시가·고가·저가·기준가(stck_oprc·stck_hgpr·stck_lwpr·stck_sdpr, KIS data.csv inquire_price 컬럼)는 12:00 픽 노트의 "시가 대비" 계산용으로
   * 2026-09-21 에 <b>맨 뒤에</b> 더했다 — 위치 record 라 기존 테스트가 위치 인자로 채우므로 중간 삽입 금지.
   */
  public record Output(
      @JsonProperty("stck_shrn_iscd") String ticker,
      @JsonProperty("stck_prpr") String currentPrice,
      @JsonProperty("prdy_vrss") String prevDiff,
      @JsonProperty("prdy_vrss_sign") String prevDiffSign,
      @JsonProperty("prdy_ctrt") String changeRate,
      @JsonProperty("acml_vol") String accumulatedVolume,
      @JsonProperty("acml_tr_pbmn") String accumulatedTradingValue,
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
      @JsonProperty("mrkt_warn_cls_code") String marketWarning,
      @JsonProperty("stck_oprc") String openPrice,
      @JsonProperty("stck_hgpr") String highPrice,
      @JsonProperty("stck_lwpr") String lowPrice,
      @JsonProperty("stck_sdpr") String basePrice
  ) {
  }
}
