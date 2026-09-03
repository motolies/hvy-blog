package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 해외주식 기간별시세 HHDFS76240000 응답 (개별주·ETF). output2 는 조회기준일(BYMD) 이하 최근 100건.
 */
public record KisOverseasDailyPriceResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output1") Meta output1,
    @JsonProperty("output2") List<Candle> output2
) implements KisEnvelope {

  /** output1: 실시간조회종목코드·소수점 자리수·전일종가 */
  public record Meta(
      @JsonProperty("rsym") String realtimeSymbol,
      @JsonProperty("zdiv") String decimals,
      @JsonProperty("nrec") String previousClose
  ) {
  }

  /** output2: 일봉 1건 */
  public record Candle(
      @JsonProperty("xymd") String tradeDate,
      @JsonProperty("clos") String close,
      @JsonProperty("open") String open,
      @JsonProperty("high") String high,
      @JsonProperty("low") String low,
      @JsonProperty("tvol") String volume,
      @JsonProperty("tamt") String tradingValue,
      @JsonProperty("rate") String changeRate,
      @JsonProperty("diff") String diff,
      @JsonProperty("sign") String sign
  ) {
  }
}
