package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 주식기본조회 CTPF1002R 응답. 상장일·상장폐지일·K200 여부·업종 코드를 준다.
 * 상장폐지 종목도 조회된다(2026-09-07 실측: 동양생명 082640 → lstg_abol_dt=20260831) — 생존편향 완화(상폐 종목 마스터 복원)에 쓴다.
 */
public record KisStockInfoResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") Output output
) implements KisEnvelope {

  /**
   * output: 종목 기본정보.
   * <p>
   * {@code pdno} 는 KIS 상품계 12자 상품번호(예 {@code 00000A005930})이지 6자 단축코드가 아니다. 마스터 키(ticker)로 쓰면
   * varchar(10) 을 넘고 기존 행도 못 찾으므로, 키는 항상 요청에 쓴 종목코드를 써야 한다.
   */
  public record Output(
      @JsonProperty("pdno") String productNo,
      @JsonProperty("prdt_abrv_name") String shortName,
      @JsonProperty("prdt_name") String name,
      @JsonProperty("mket_id_cd") String marketId,
      @JsonProperty("scty_grp_id_cd") String securityGroup,
      @JsonProperty("std_pdno") String standardCode,
      @JsonProperty("scts_mket_lstg_dt") String kospiListingDate,
      @JsonProperty("kosdaq_mket_lstg_dt") String kosdaqListingDate,
      @JsonProperty("lstg_abol_dt") String delistingDate,
      @JsonProperty("kospi200_item_yn") String kospi200,
      @JsonProperty("idx_bztp_lcls_cd") String sectorLarge,
      @JsonProperty("idx_bztp_mcls_cd") String sectorMid,
      @JsonProperty("idx_bztp_scls_cd") String sectorSmall,
      @JsonProperty("idx_bztp_mcls_cd_name") String sectorMidName,
      @JsonProperty("std_idst_clsf_cd") String industryCode,
      @JsonProperty("std_idst_clsf_cd_name") String industryName,
      @JsonProperty("lstg_stqt") String listedShares,
      @JsonProperty("papr") String parValue,
      @JsonProperty("setl_mmdd") String settleMonthDay,
      @JsonProperty("tr_stop_yn") String suspended,
      @JsonProperty("admn_item_yn") String administrative
  ) {
  }
}
