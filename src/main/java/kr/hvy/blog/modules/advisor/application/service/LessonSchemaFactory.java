package kr.hvy.blog.modules.advisor.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.LessonScope;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;

/**
 * 교훈 제안 출력의 strict JSON 스키마. condition 의 각 키는 nullable 로 두되 LessonService 가 "최소 하나는 non-null" 을 검증한다.
 */
public final class LessonSchemaFactory {

  private LessonSchemaFactory() {
  }

  public static String schemaJson(List<String> sectorCodes) {
    Map<String, Object> condition = AdviceSchemaFactory.object(Map.of(
        "regime", nullableEnum(List.of(MarketRegimeCode.RISK_ON.getCode(), MarketRegimeCode.NEUTRAL.getCode(), MarketRegimeCode.RISK_OFF.getCode())),
        "signal", nullableEnum(SignalCode.scorable().stream().map(SignalCode::getCode).toList()),
        "op", nullableEnum(List.of(">=", "<", ">", "<=")),
        "pct", nullable("number"),
        "sector", sectorCodes == null || sectorCodes.isEmpty() ? nullable("string") : nullableEnum(sectorCodes)));
    Map<String, Object> evidence = AdviceSchemaFactory.object(Map.of(
        "n", Map.of("type", "integer"),
        "from", AdviceSchemaFactory.string(),
        "to", AdviceSchemaFactory.string(),
        "excess", AdviceSchemaFactory.number(),
        "t", AdviceSchemaFactory.number()));
    Map<String, Object> proposal = AdviceSchemaFactory.object(Map.of(
        "scope", AdviceSchemaFactory.enumOf(List.of(LessonScope.SIGNAL.getCode(), LessonScope.REGIME.getCode(), LessonScope.SECTOR.getCode(),
            LessonScope.CALIBRATION.getCode())),
        "condition", condition,
        "observation", AdviceSchemaFactory.string(),
        "evidence", evidence,
        "rule", AdviceSchemaFactory.string()));
    Map<String, Object> retire = AdviceSchemaFactory.object(Map.of(
        "lessonId", Map.of("type", "integer"),
        "reason", AdviceSchemaFactory.string()));
    Map<String, Object> root = AdviceSchemaFactory.object(Map.of(
        "proposals", AdviceSchemaFactory.array(proposal),
        "nullResults", AdviceSchemaFactory.array(AdviceSchemaFactory.string()),
        "retireCandidates", AdviceSchemaFactory.array(retire)));
    return AdvisorJson.write(root);
  }

  static Map<String, Object> nullable(String type) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", List.of(type, "null"));
    return schema;
  }

  static Map<String, Object> nullableEnum(List<String> values) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", List.of("string", "null"));
    java.util.ArrayList<Object> withNull = new java.util.ArrayList<>(values);
    withNull.add(null);
    schema.put("enum", withNull);
    return schema;
  }
}
