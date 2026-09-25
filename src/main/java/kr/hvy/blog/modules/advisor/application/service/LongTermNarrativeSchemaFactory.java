package kr.hvy.blog.modules.advisor.application.service;

import java.util.List;
import java.util.Map;

/**
 * 장기 서술 출력 JSON 스키마(OpenAI strict, M8). ticker 는 규칙이 확정한 픽 N 개의 enum 이라 후보 밖 종목을 API 계층에서 막는다.
 * 순서·개수는 JSON 스키마로 강제할 수 없어 LongTermNarrativeGuard 가 규칙 결과에 맞춘다.
 */
public final class LongTermNarrativeSchemaFactory {

  private LongTermNarrativeSchemaFactory() {
  }

  /**
   * 규칙 픽 티커(순위 순)로 스키마를 만든다.
   */
  public static Map<String, Object> schema(List<String> pickTickers) {
    Map<String, Object> narrative = AdviceSchemaFactory.object(Map.of(
        "ticker", AdviceSchemaFactory.enumOf(pickTickers),
        "thesis", AdviceSchemaFactory.string(),
        "risk", AdviceSchemaFactory.string()));
    return AdviceSchemaFactory.object(Map.of(
        "picks", AdviceSchemaFactory.array(narrative),
        "summary", AdviceSchemaFactory.string()));
  }

  public static String schemaJson(List<String> pickTickers) {
    return AdvisorJson.write(schema(pickTickers));
  }
}
