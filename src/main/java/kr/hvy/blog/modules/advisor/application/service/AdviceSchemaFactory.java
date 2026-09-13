package kr.hvy.blog.modules.advisor.application.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.InvalidationType;
import kr.hvy.blog.modules.advisor.domain.code.MarketRegimeCode;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.TrendHorizon;
import kr.hvy.common.core.code.base.EnumCode;

/**
 * 판단 출력 JSON 스키마(OpenAI strict). 그날의 후보 티커·섹터 코드를 enum 으로 주입해 환각을 API 계층에서 막는다.
 * <p>
 * strict 규칙: 모든 객체는 additionalProperties=false, 모든 속성이 required. 이산 확신값은 문자열 enum 으로 받아 가드가 double 로 바꾼다.
 */
public final class AdviceSchemaFactory {

  public static final List<String> CONVICTIONS = List.of("0.55", "0.60", "0.65", "0.70", "0.75", "0.80", "0.85", "0.90");

  private AdviceSchemaFactory() {
  }

  /**
   * 후보 티커·섹터 코드로 스키마를 만든다. sectorCodes 가 비면 섹터 code 는 자유 문자열(가드가 검증).
   */
  public static Map<String, Object> schema(List<String> candidateTickers, List<String> sectorCodes) {
    Map<String, Object> pick = object(Map.of(
        "ticker", enumOf(candidateTickers),
        "direction", enumOf(List.of(PickDirection.LONG.getCode(), PickDirection.AVOID.getCode())),
        "conviction", enumOf(CONVICTIONS),
        "thesis", string(),
        "risk", string(),
        "citedFeatures", array(object(Map.of("name", string(), "value", number())))));
    Map<String, Object> regime = object(Map.of(
        "code", enumOf(List.of(MarketRegimeCode.RISK_ON.getCode(), MarketRegimeCode.NEUTRAL.getCode(), MarketRegimeCode.RISK_OFF.getCode())),
        "kospiDir", enumOf(List.of(DirectionCall.UP.getCode(), DirectionCall.NEUTRAL.getCode(), DirectionCall.DOWN.getCode())),
        "kosdaqDir", enumOf(List.of(DirectionCall.UP.getCode(), DirectionCall.NEUTRAL.getCode(), DirectionCall.DOWN.getCode())),
        "pUp", enumOf(CONVICTIONS),
        "rationale", string()));
    Map<String, Object> sector = object(Map.of(
        "code", sectorCodes == null || sectorCodes.isEmpty() ? string() : enumOf(sectorCodes),
        "reason", string()));
    // 추세 지속 전망: 규칙이 확정한 추세가 얼마나 더 갈지(버킷) + 깨졌다고 볼 첫 신호(MA 이벤트 enum, 수치 없음)
    Map<String, Object> outlook = object(Map.of(
        "persist", enumOf(codes(TrendHorizon.values())),
        "confidence", enumOf(CONVICTIONS),
        "invalidation", enumOf(codes(InvalidationType.values()))));
    Map<String, Object> trendOutlook = object(Map.of(
        "kospi", outlook,
        "kosdaq", outlook));
    return object(Map.of(
        "regime", regime,
        "trendOutlook", trendOutlook,
        "sectors", array(sector),
        "picks", array(pick),
        "summary", string()));
  }

  private static List<String> codes(EnumCode<String>[] values) {
    List<String> list = new ArrayList<>();
    for (EnumCode<String> v : values) {
      list.add(v.getCode());
    }
    return list;
  }

  /**
   * 스키마 JSON 문자열.
   */
  public static String schemaJson(List<String> candidateTickers, List<String> sectorCodes) {
    return AdvisorJson.write(schema(candidateTickers, sectorCodes));
  }

  static Map<String, Object> object(Map<String, Object> properties) {
    // 속성 순서를 고정한다 (해시·비교 안정성)
    Map<String, Object> ordered = new LinkedHashMap<>();
    List<String> keys = new ArrayList<>(properties.keySet());
    keys.sort(String::compareTo);
    for (String key : keys) {
      ordered.put(key, properties.get(key));
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", ordered);
    schema.put("required", keys);
    schema.put("additionalProperties", false);
    return schema;
  }

  static Map<String, Object> array(Map<String, Object> items) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("items", items);
    return schema;
  }

  static Map<String, Object> string() {
    return Map.of("type", "string");
  }

  static Map<String, Object> number() {
    return Map.of("type", "number");
  }

  static Map<String, Object> enumOf(List<String> values) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("enum", values);
    return schema;
  }
}
