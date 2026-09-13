package kr.hvy.blog.modules.advisor.domain.model;

import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import lombok.Builder;

/**
 * 지수 1개에 대한 LLM 의 추세 지속 전망(가드 통과값). tb_advisor_advice.outlook_json 에 지수별로 저장되고 h=20 패스에서 TREND·TREND_INV 로 채점된다.
 *
 * @param indexCode    0001 | 1001
 * @param persist      지속 기간 버킷
 * @param confidence   확신 (CONVICTIONS 이산값)
 * @param invalidation 무효화 첫 신호 (NONE 이면 TREND_INV 채점 제외)
 */
@Builder(toBuilder = true)
public record TrendOutlook(String indexCode, TrendHorizon persist, double confidence, InvalidationType invalidation) {
}
