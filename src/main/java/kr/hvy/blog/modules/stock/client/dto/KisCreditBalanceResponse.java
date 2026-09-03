package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국내주식 신용잔고 일별추이 FHPST04760000 응답. deal_date 매매일 기준으로 융자·대주 잔고를 준다.
 */
public record KisCreditBalanceResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") List<Row> output
) implements KisEnvelope {

  /** 일자 1건 */
  public record Row(
      @JsonProperty("deal_date") String dealDate,
      @JsonProperty("stlm_date") String settlementDate,
      @JsonProperty("whol_loan_rmnd_stcn") String loanBalanceQty,
      @JsonProperty("whol_loan_rmnd_amt") String loanBalanceAmt,
      @JsonProperty("whol_loan_rmnd_rate") String loanBalanceRate,
      @JsonProperty("whol_stln_rmnd_stcn") String stockLoanBalanceQty
  ) {
  }
}
