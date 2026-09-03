package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 종목별 투자자매매동향(일별) FHPTJ04160001 응답. output2 가 일자별 순매수(수량·대금)다.
 * output1(현재가 요약)은 쓰지 않아 매핑하지 않는다(알 수 없는 필드는 무시).
 */
public record KisInvestorDailyResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output2") List<Row> output2
) implements KisEnvelope {

  /** 일자 1건 */
  public record Row(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("stck_clpr") String close,
      @JsonProperty("frgn_ntby_qty") String foreignNetQty,
      @JsonProperty("prsn_ntby_qty") String individualNetQty,
      @JsonProperty("orgn_ntby_qty") String institutionNetQty,
      @JsonProperty("fund_ntby_qty") String pensionNetQty,
      @JsonProperty("frgn_ntby_tr_pbmn") String foreignNetAmt,
      @JsonProperty("prsn_ntby_tr_pbmn") String individualNetAmt,
      @JsonProperty("orgn_ntby_tr_pbmn") String institutionNetAmt,
      @JsonProperty("fund_ntby_tr_pbmn") String pensionNetAmt,
      @JsonProperty("etc_ntby_tr_pbmn") String etcNetAmt,
      @JsonProperty("etc_corp_ntby_tr_pbmn") String etcCorpNetAmt,
      @JsonProperty("etc_orgt_ntby_tr_pbmn") String etcOrgNetAmt
  ) {
  }
}
