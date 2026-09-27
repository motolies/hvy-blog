package kr.hvy.blog.modules.advisor.client.llm;

import java.util.List;

/**
 * 판단 모델의 구조화 출력. JSON 스키마(AdviceSchemaFactory)와 필드가 1:1 이며 enum 값은 문자열로 받아 AdviceGuard 가 검증·변환한다
 * (모르는 값이 와도 예외가 아니라 "제거 + 경고" 가 되게).
 */
public record AdviceResponse(Regime regime, TrendOutlookView trendOutlook, List<SectorView> sectors, List<Pick> picks, String summary) {

  public record Regime(String code, String kospiDir, String kosdaqDir, String pUp, String rationale) {
  }

  /** 지수별 추세 지속 전망 (advice-v2) */
  public record TrendOutlookView(Outlook kospi, Outlook kosdaq) {
  }

  public record Outlook(String persist, String confidence, String invalidation) {
  }

  public record SectorView(String code, String reason) {
  }

  /**
   * citedNews 는 advice-v4 — 프롬프트 news 블록의 헤드라인 id (없으면 null·빈 목록). direction 은 advice-v9(매수 전용) 스키마에 없어 보통 null 이다 —
   * 필드를 남긴 이유는 방어: 스키마 밖 응답이 AVOID 를 실어 오면 가드가 제거·기록해야 하므로 역직렬화에서 조용히 버리지 않는다.
   */
  public record Pick(String ticker, String direction, String conviction, String thesis, String risk, List<Cited> citedFeatures, List<String> citedNews) {
  }

  public record Cited(String name, double value) {
  }
}
