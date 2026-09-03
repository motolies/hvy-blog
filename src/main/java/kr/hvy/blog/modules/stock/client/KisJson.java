package kr.hvy.blog.modules.stock.client;

import tools.jackson.databind.json.JsonMapper;

/**
 * KIS 응답 파싱 전용 Jackson 3 매퍼.
 * <p>
 * KIS 응답은 필드가 많고 문서와 다른 필드가 종종 추가되므로 알 수 없는 필드는 무시한다(Jackson 3 기본값).
 */
public final class KisJson {

  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private KisJson() {
  }

  /**
   * 본문을 지정 타입으로 역직렬화한다. 실패 시 Jackson 예외(unchecked)를 그대로 전파한다.
   */
  public static <T> T read(String body, Class<T> type) {
    return MAPPER.readValue(body, type);
  }

  /**
   * 객체를 JSON 문자열로 직렬화한다 (raw_json 보관용).
   */
  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  /**
   * 본문을 지정 타입으로 역직렬화하되, 실패하면 null 을 돌려준다. 오류 본문(HTML 등) 판정에 쓴다.
   */
  static <T> T tryRead(String body, Class<T> type) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return MAPPER.readValue(body, type);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
