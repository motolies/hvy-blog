package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국내주식 종합 시황/공시(제목) 응답 (news-title, [국내주식-141]). 제목·작성 시각·관련 종목코드(iscd1~5)만 준다(본문 없음).
 * 필드명은 KIS 저장소 data.csv column_mapping 기준. TR ID·경로·응답 배열 키(output1)는 KisNewsTitleManualTest 로 실측한다.
 */
public record KisNewsTitleResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output1") List<Row> output1,
    @JsonProperty("output") List<Row> output
) implements KisEnvelope {

  /**
   * output1 이 없고 output 으로 오는 경우(문서와 실제가 다른 API 가 있다)를 흡수한다.
   */
  public List<Row> rows() {
    if (output1 != null && !output1.isEmpty()) {
      return output1;
    }
    return output == null ? List.of() : output;
  }

  /** 제목 1건 */
  public record Row(
      @JsonProperty("cntt_usiq_srno") String serialNo,
      @JsonProperty("news_ofer_entp_code") String providerCode,
      @JsonProperty("data_dt") String date,
      @JsonProperty("data_tm") String time,
      @JsonProperty("hts_pbnt_titl_cntt") String title,
      @JsonProperty("news_lrdv_code") String categoryCode,
      @JsonProperty("dorg") String origin,
      @JsonProperty("iscd1") String iscd1,
      @JsonProperty("iscd2") String iscd2,
      @JsonProperty("iscd3") String iscd3,
      @JsonProperty("iscd4") String iscd4,
      @JsonProperty("iscd5") String iscd5
  ) {
  }
}
