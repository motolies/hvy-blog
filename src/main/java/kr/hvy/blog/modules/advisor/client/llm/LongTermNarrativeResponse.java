package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;

/**
 * 장기(H60·H180) 서술 모델의 구조화 출력(M8, advice-longterm-v1). 선택·순위는 규칙이 이미 확정했으므로 종목별 thesis/risk 와 총평만 받는다.
 * 스키마(LongTermNarrativeSchemaFactory)의 ticker enum 이 규칙 픽 N 개로 묶여 있고, 그래도 어긋난 응답은 LongTermNarrativeGuard 가 규칙 쪽으로 맞춘다.
 */
public record LongTermNarrativeResponse(List<Narrative> picks, String summary) {

  public record Narrative(String ticker, String thesis, String risk) {
  }
}
