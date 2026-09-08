package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 시장별 투자자매매동향(일별) FHPTJ04040000 응답. output 하나에 일자별 투자자 15주체의 순매수 수량·대금이 온다.
 * <p>
 * 필드명은 KIS data.csv column_mapping 기준(claudedocs/kis/data.csv). 접미사가 규칙적이지 않아 전부 명시한다:
 * 수량은 12개 {@code _ntby_qty} + 3개 {@code _ntby_vol}(사모·기타단체·기타법인), 대금은 13개 {@code _ntby_tr_pbmn}
 * + 2개 {@code _ntby_pbmn}(외국인 등록·비등록). 지수 9필드는 쓰지 않아 매핑하지 않는다.
 */
public record KisMarketInvestorResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") List<Row> output
) implements KisEnvelope {

  /** 일자 1건 */
  public record Row(
      @JsonProperty("stck_bsop_date") String tradeDate,
      @JsonProperty("frgn_ntby_qty") String foreignNetQty,
      @JsonProperty("frgn_ntby_tr_pbmn") String foreignNetAmt,
      @JsonProperty("frgn_reg_ntby_qty") String foreignRegNetQty,
      @JsonProperty("frgn_reg_ntby_pbmn") String foreignRegNetAmt,
      @JsonProperty("frgn_nreg_ntby_qty") String foreignNregNetQty,
      @JsonProperty("frgn_nreg_ntby_pbmn") String foreignNregNetAmt,
      @JsonProperty("prsn_ntby_qty") String individualNetQty,
      @JsonProperty("prsn_ntby_tr_pbmn") String individualNetAmt,
      @JsonProperty("orgn_ntby_qty") String institutionNetQty,
      @JsonProperty("orgn_ntby_tr_pbmn") String institutionNetAmt,
      @JsonProperty("scrt_ntby_qty") String securitiesNetQty,
      @JsonProperty("scrt_ntby_tr_pbmn") String securitiesNetAmt,
      @JsonProperty("ivtr_ntby_qty") String investTrustNetQty,
      @JsonProperty("ivtr_ntby_tr_pbmn") String investTrustNetAmt,
      @JsonProperty("pe_fund_ntby_vol") String privateFundNetQty,
      @JsonProperty("pe_fund_ntby_tr_pbmn") String privateFundNetAmt,
      @JsonProperty("bank_ntby_qty") String bankNetQty,
      @JsonProperty("bank_ntby_tr_pbmn") String bankNetAmt,
      @JsonProperty("insu_ntby_qty") String insuranceNetQty,
      @JsonProperty("insu_ntby_tr_pbmn") String insuranceNetAmt,
      @JsonProperty("mrbn_ntby_qty") String merchantBankNetQty,
      @JsonProperty("mrbn_ntby_tr_pbmn") String merchantBankNetAmt,
      @JsonProperty("fund_ntby_qty") String pensionNetQty,
      @JsonProperty("fund_ntby_tr_pbmn") String pensionNetAmt,
      @JsonProperty("etc_ntby_qty") String otherNetQty,
      @JsonProperty("etc_ntby_tr_pbmn") String otherNetAmt,
      @JsonProperty("etc_orgt_ntby_vol") String otherOrgNetQty,
      @JsonProperty("etc_orgt_ntby_tr_pbmn") String otherOrgNetAmt,
      @JsonProperty("etc_corp_ntby_vol") String otherCorpNetQty,
      @JsonProperty("etc_corp_ntby_tr_pbmn") String otherCorpNetAmt
  ) {
  }
}
