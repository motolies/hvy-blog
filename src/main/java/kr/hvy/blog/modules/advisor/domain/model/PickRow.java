package kr.hvy.blog.modules.advisor.domain.model;

import java.util.List;
import kr.hvy.blog.modules.advisor.domain.code.PickAction;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import lombok.Builder;

/**
 * 픽 1개 (tb_advisor_pick). 반드시 같은 advice 의 후보여야 한다.
 */
@Builder(toBuilder = true)
public record PickRow(
    String ticker,
    int pickRank,
    PickDirection direction,
    double conviction,
    String thesis,
    String riskNote,
    List<CitedFeature> cited,
    /** 인용한 헤드라인 id (advice-v4, 가드가 입력에 있던 id 만 남긴다) */
    List<String> citedNews,
    /** 아침 재판정의 조치(KEEP·ADD·DROP). 컬럼은 M4 에서 추가되며 그 전(과 MORNING 이 아닌 판단)은 null — 비교는 null 이면 코드 집합 차이로 계산한다 */
    PickAction action,
    /** 조치 사유 (action 과 짝, nullable) */
    String actionReason) {
}
