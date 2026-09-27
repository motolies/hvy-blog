package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;

/**
 * 아침 재판정(morning-v1) 판단 모델의 구조화 출력. decisions 는 저녁 픽마다 KEEP/DROP 과 사유, additions 는 저녁 후보(픽 제외) 안에서만 고른 추가 픽,
 * summary 는 밤사이 정보가 저녁 판단을 어떻게 바꿨는지의 요약이다. 범위 위반은 스키마 enum 이 1차, MorningAdviceGuard 가 2차로 막는다.
 */
public record MorningAdviceResponse(List<Decision> decisions, List<Addition> additions, String summary) {

  /** 저녁 픽 1개에 대한 조치 (KEEP | DROP) */
  public record Decision(String ticker, String action, String reason) {
  }

  /** 저녁 후보 중 새로 올릴 매수 픽 1개. direction 은 morning-v2 스키마에 없어 보통 null — AVOID 가 오면 가드가 제거·기록한다(방어용 필드) */
  public record Addition(String ticker, String direction, String conviction, String thesis, String risk, String reason) {
  }
}
