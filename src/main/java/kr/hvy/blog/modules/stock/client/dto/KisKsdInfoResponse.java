package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 예탁원정보(ksdinfo/*) 공통 응답. 7종의 output1 컬럼이 모두 달라 맵으로 받고 매퍼가 유형별로 해석한다.
 */
public record KisKsdInfoResponse(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output1") List<Map<String, String>> output1
) implements KisEnvelope {
}
