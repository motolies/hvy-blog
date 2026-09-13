package kr.hvy.blog.modules.advisor.application.chat.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;

/**
 * 도구 반환값 유틸. 도구는 문자열이 아니라 {@code Map} 을 돌려준다 — Spring AI 가 JSON 으로 직렬화하므로 문자열을 돌려주면 따옴표가 한 번 더 감싸져
 * 토큰이 낭비된다. null 값은 넣지 않는다(키 자체를 생략). 소수는 4자리, 가격은 정수.
 * 오류도 예외가 아니라 {@code {"error":…}} 로 돌려준다 — 예외는 도구 루프를 죽여 사용자가 무응답을 받는다.
 */
public final class ToolJson {

  public static final String ERROR_NO_DATA = "no_data";
  public static final String ERROR_TIMEOUT = "timeout";
  public static final String ERROR_DEADLINE = "deadline";
  public static final String ERROR_BAD_ARGUMENT = "bad_argument";
  public static final String ERROR_INTERNAL = "internal";

  private ToolJson() {
  }

  /**
   * 순서를 유지하는 결과 맵.
   */
  public static Map<String, Object> obj() {
    return new LinkedHashMap<>();
  }

  /**
   * null 이 아닐 때만 넣는다.
   */
  public static Map<String, Object> put(Map<String, Object> m, String key, Object value) {
    if (value != null) {
      m.put(key, value);
    }
    return m;
  }

  /**
   * 오류 결과. asOf 가 있으면 함께 실어 모델이 기준일을 알게 한다.
   */
  public static Map<String, Object> error(String code, String message, LocalDate asOf) {
    Map<String, Object> m = obj();
    m.put("error", code);
    put(m, "message", message);
    put(m, "asOf", asOf == null ? null : asOf.toString());
    return m;
  }

  public static Map<String, Object> noData(LocalDate asOf) {
    return error(ERROR_NO_DATA, null, asOf);
  }

  /**
   * 테스트·로그용 직렬화(AdvisorJson: NON_NULL).
   */
  public static String write(Object value) {
    return AdvisorJson.write(value);
  }

  /**
   * 소수 4자리 반올림(null·NaN 은 null). 비율·수익률에 쓴다.
   */
  public static Double r4(Number value) {
    if (value == null) {
      return null;
    }
    double d = value.doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return null;
    }
    return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }

  /**
   * 정수 반올림(null·NaN 은 null). 가격·금액에 쓴다.
   */
  public static Long r0(Number value) {
    if (value == null) {
      return null;
    }
    double d = value.doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return null;
    }
    return Math.round(d);
  }
}
