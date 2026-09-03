package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 재무 API 공통 응답. output 은 결산기(stac_yymm) 별 행이며 API 마다 컬럼이 달라 맵으로 받는다.
 */
public record KisFinancialResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") List<Map<String, String>> output
) implements KisEnvelope {
}
