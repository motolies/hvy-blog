package kr.hvy.blog.modules.stock.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 봉투 필드만 읽는 최소 응답. 오류 본문의 msg_cd 판정(EGW00201 등)에 쓴다.
 */
public record KisEnvelopeOnly(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1
) implements KisEnvelope {
}
