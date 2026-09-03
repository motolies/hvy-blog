package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 국내휴장일조회 CTCA0903R 응답. 기준일부터 일자별 영업일/거래일/개장일/결제일 여부를 준다.
 * 연속조회는 tr_cont 헤더 + 본문 ctx_area_fk/nk 를 다음 요청 파라미터로 승계한다.
 */
public record KisHolidayResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("ctx_area_fk") String ctxAreaFk,
    @JsonProperty("ctx_area_nk") String ctxAreaNk,
    @JsonProperty("output") List<Day> output
) implements KisEnvelope {

  /** 일자 1건 */
  public record Day(
      @JsonProperty("bass_dt") String date,
      @JsonProperty("wday_dvsn_cd") String weekdayCode,
      @JsonProperty("bzdy_yn") String businessDay,
      @JsonProperty("tr_day_yn") String tradingDay,
      @JsonProperty("opnd_yn") String openDay,
      @JsonProperty("sttl_day_yn") String settlementDay
  ) {
  }
}
