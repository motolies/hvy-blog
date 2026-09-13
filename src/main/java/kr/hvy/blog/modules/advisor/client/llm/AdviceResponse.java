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

  public record Pick(String ticker, String direction, String conviction, String thesis, String risk, List<Cited> citedFeatures) {
  }

  public record Cited(String name, double value) {
  }
}
