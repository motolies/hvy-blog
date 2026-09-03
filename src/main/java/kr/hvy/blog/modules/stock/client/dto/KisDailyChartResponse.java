package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국내주식기간별시세(일/주/월/년) FHKST03010100 응답.
 * <p>
 * 숫자 필드는 KIS 가 문자열로 주므로 String 으로 받고 {@code KisValues} 로 변환한다.
 * output2 는 최신 → 과거 순으로 최대 100건이다.
 */
public record KisDailyChartResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output1") Summary output1,
    @JsonProperty("output2") List<Candle> output2
) implements KisEnvelope {

  /** output1: 종목 요약 (현재가, 상장주수, 시총 등) */
  public record Summary(
      @JsonProperty("hts_kor_isnm") String stockName,
      @JsonProperty("stck_shrn_iscd") String ticker,
      @JsonProperty("stck_prpr") String currentPrice,
      @JsonProperty("prdy_vrss") String prevDiff,
      @JsonProperty("prdy_vrss_sign") String prevDiffSign,
      @JsonProperty("prdy_ctrt") String changeRate,
      @JsonProperty("acml_vol") String accumulatedVolume,
      @JsonProperty("lstn_stcn") String listedShares,
      @JsonProperty("hts_avls") String marketCap
  ) {
  }

  /** output2: 일봉 1건 */
  public record Candle(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("stck_clpr") String close,
      @JsonProperty("stck_oprc") String open,
      @JsonProperty("stck_hgpr") String high,
      @JsonProperty("stck_lwpr") String low,
      @JsonProperty("acml_vol") String volume,
      @JsonProperty("acml_tr_pbmn") String tradingValue,
      @JsonProperty("flng_cls_code") String flngClsCode,
      @JsonProperty("prtt_rate") String splitRate,
      @JsonProperty("mod_yn") String modYn,
      @JsonProperty("prdy_vrss_sign") String prevDiffSign,
      @JsonProperty("prdy_vrss") String prevDiff,
      @JsonProperty("revl_issu_reas") String revalReason
  ) {
  }
}
