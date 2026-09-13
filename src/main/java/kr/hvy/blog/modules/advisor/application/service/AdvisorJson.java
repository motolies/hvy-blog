package kr.hvy.blog.modules.advisor.application.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * advisor 모듈 전용 Jackson 3 매퍼. JSONB 컬럼 원문·LLM 입력 payload 직렬화에 쓴다.
 * <p>
 * null 필드는 생략해 프롬프트 토큰을 아끼고(입력 JSON 의 키가 곧 토큰), 키 순서는 삽입 순서를 유지해 해시·비교가 안정적이다.
 */
public final class AdvisorJson {

  public static final JsonMapper MAPPER = JsonMapper.builder()
      .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
      .build();

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
  };

  private AdvisorJson() {
  }

  /**
   * 객체를 JSON 문자열로 직렬화한다.
   */
  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  /**
   * JSON 문자열을 지정 타입으로 역직렬화한다. 실패 시 Jackson 예외(unchecked)를 그대로 전파한다.
   */
  public static <T> T read(String json, Class<T> type) {
    return MAPPER.readValue(json, type);
  }

  /**
   * JSON 객체 문자열을 Map 으로 읽는다 (null·빈 문자열은 빈 Map).
   */
  public static Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return MAPPER.readValue(json, MAP);
  }

  /**
   * 임의 값을 Map 으로 변환한다 (JSONB 메타데이터용).
   */
  public static Map<String, Object> toMap(Object value) {
    return MAPPER.convertValue(value, MAP);
  }
}
