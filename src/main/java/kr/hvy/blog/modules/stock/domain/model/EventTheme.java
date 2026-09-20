package kr.hvy.blog.modules.stock.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GDELT 테마 1개 (yml {@code gdelt.themes} "코드:표시명:쿼리"). 코드는 tb_stock_event_timeline.theme_code 이자 tb_stock_news.provider_code 이므로
 * 10자 이내 대문자·숫자·밑줄만 허용한다. 쿼리는 GDELT DOC API 문법 그대로(따옴표·OR·sourcelang: 포함).
 */
public record EventTheme(String code, String name, String query) {

  private static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{1,10}$");

  /**
   * "KR_GEO:한반도·북한:(\"North Korea\" OR …) sourcelang:english" 형식 1줄을 파싱한다.
   */
  public static EventTheme parse(String spec) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalArgumentException("gdelt.themes 항목이 비어 있습니다");
    }
    String[] parts = spec.trim().split(":", 3);
    if (parts.length != 3 || parts[2].isBlank()) {
      throw new IllegalArgumentException("gdelt.themes 형식 오류 (코드:표시명:쿼리): " + spec);
    }
    String code = parts[0].trim().toUpperCase();
    if (!CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("gdelt 테마 코드는 대문자·숫자·밑줄 10자 이내여야 합니다: " + code);
    }
    return new EventTheme(code, parts[1].trim(), parts[2].trim());
  }

  /**
   * 목록 전부 파싱. 코드 중복은 거부한다.
   */
  public static List<EventTheme> parseAll(List<String> specs) {
    List<EventTheme> result = new ArrayList<>();
    if (specs != null) {
      for (String spec : specs) {
        EventTheme theme = parse(spec);
        if (result.stream().anyMatch(t -> t.code().equals(theme.code()))) {
          throw new IllegalArgumentException("gdelt 테마 코드 중복: " + theme.code());
        }
        result.add(theme);
      }
    }
    return result;
  }
}
