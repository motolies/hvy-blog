package kr.hvy.blog.modules.stock.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.apache.commons.lang3.StringUtils;

/**
 * KIS 응답 값 변환 유틸.
 * <p>
 * KIS 는 모든 숫자를 문자열로 주고 결측을 빈 문자열("")이나 "-" 로 표기한다. 이를 관대하게 파싱해
 * 결측은 null 로 돌려준다. 콤마 구분(1,234)과 선행 부호(+/-)도 허용한다.
 */
public final class KisValues {

  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

  private KisValues() {
  }

  /**
   * 숫자 문자열을 BigDecimal 로 변환한다. 결측이면 null.
   */
  public static BigDecimal decimal(String raw) {
    String normalized = normalize(raw);
    return normalized == null ? null : new BigDecimal(normalized);
  }

  /**
   * 숫자 문자열을 Long 으로 변환한다. 소수부가 있으면 버린다. 결측이면 null.
   */
  public static Long longValue(String raw) {
    BigDecimal value = decimal(raw);
    return value == null ? null : value.longValue();
  }

  /**
   * 숫자 문자열을 long 으로 변환한다. 결측이면 defaultValue.
   */
  public static long longValue(String raw, long defaultValue) {
    Long value = longValue(raw);
    return value == null ? defaultValue : value;
  }

  /**
   * 숫자 문자열을 Integer 로 변환한다. 결측이면 null.
   */
  public static Integer intValue(String raw) {
    BigDecimal value = decimal(raw);
    return value == null ? null : value.intValue();
  }

  /**
   * yyyyMMdd 문자열을 LocalDate 로 변환한다. 결측이면 null.
   */
  public static LocalDate date(String raw) {
    String trimmed = StringUtils.trimToNull(raw);
    if (trimmed == null || "00000000".equals(trimmed)) {
      return null;
    }
    try {
      return LocalDate.parse(trimmed, YYYYMMDD);
    } catch (DateTimeParseException e) {
      // KIS 는 자리표시자·공백 섞인 값을 주기도 한다. 한 행의 날짜 불량이 전체 페이지를 버리게 하지 않는다
      return null;
    }
  }

  /**
   * LocalDate 를 KIS 요청 형식(yyyyMMdd)으로 변환한다.
   */
  public static String format(LocalDate date) {
    return date.format(YYYYMMDD);
  }

  /**
   * Y/N 플래그를 boolean 으로 변환한다. Y 이외는 모두 false.
   */
  public static boolean flag(String raw) {
    return "Y".equalsIgnoreCase(StringUtils.trimToEmpty(raw));
  }

  /**
   * 빈 문자열/"-"/공백을 null 로, 콤마와 선행 + 를 제거해 BigDecimal 이 읽을 수 있는 형태로 만든다.
   */
  private static String normalize(String raw) {
    String trimmed = StringUtils.trimToNull(raw);
    if (trimmed == null || "-".equals(trimmed)) {
      return null;
    }
    String cleaned = trimmed.replace(",", "");
    if (cleaned.startsWith("+")) {
      cleaned = cleaned.substring(1);
    }
    return cleaned.isEmpty() ? null : cleaned;
  }
}
