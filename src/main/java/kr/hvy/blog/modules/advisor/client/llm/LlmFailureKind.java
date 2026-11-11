package kr.hvy.blog.modules.advisor.client.llm;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 1순위 공급자 호출 실패의 분류. 폴백 여부와 차단(재시도 보류) 시간을 이 분류가 정한다({@link FallbackChatModel}·{@link LlmProviderGate}).
 * <ul>
 *   <li>EXHAUSTED — 기다려도 풀리지 않는 소진(402 결제 문제, 지출 한도 400, 티어 월 상한 429). 길게 차단한다</li>
 *   <li>UNAVAILABLE — 일시 장애(rate limit 429·408·409·5xx·529·타임아웃·연결 실패). 재시도 후에도 남으면 짧게 차단한다</li>
 *   <li>MISCONFIGURED — 키·권한·모델 ID 오류(401·403·404). 사람이 고쳐야 하므로 #hvy-error 로 알린다</li>
 *   <li>REQUEST_INVALID — 요청 자체의 오류(그 외 400·413·422). 다른 공급자로 넘기면 버그가 가려지므로 폴백하지 않는다</li>
 * </ul>
 * code 는 상수명과 같다(advisor enum 규약).
 */
@Getter
@AllArgsConstructor
public enum LlmFailureKind implements EnumCode<String> {
  EXHAUSTED("EXHAUSTED", "크레딧·지출 한도 소진", true),
  UNAVAILABLE("UNAVAILABLE", "일시 장애(rate limit·5xx·과부하·타임아웃)", true),
  MISCONFIGURED("MISCONFIGURED", "키·권한·모델 ID 오류", true),
  REQUEST_INVALID("REQUEST_INVALID", "요청 형식 오류 — 폴백하지 않는다", false);

  private final String code;
  private final String desc;
  /** true 면 다른 공급자로 넘긴다 */
  private final boolean fallbackable;
}
