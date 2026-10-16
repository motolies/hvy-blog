package kr.hvy.blog.modules.advisor.domain.code;

import java.util.Arrays;
import java.util.Optional;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MCP screenStocks 필터 연산자. SQL 에 보간되는 값은 이 enum 의 {@link #getSql()} 뿐이다 — 모델이 준 문자열은 {@link #parse(String)} 를 통과해야 SQL 에 닿는다
 * ({@link MetricColumn} 과 같은 규약). 값은 바인딩한다. (2026-10-16, plan/mcp.md P2 metricTopN)
 */
@Getter
@AllArgsConstructor
public enum ScreenOp implements EnumCode<String> {
  GTE("GTE", "이상", ">="),
  GT("GT", "초과", ">"),
  LTE("LTE", "이하", "<="),
  LT("LT", "미만", "<");

  private final String code;
  private final String desc;
  /** SQL 비교 연산자 — 보간되는 유일한 값 */
  private final String sql;

  /**
   * 상수명(GTE) 또는 기호(>=), 대소문자·공백 무관. 모르면 빈 Optional.
   */
  public static Optional<ScreenOp> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String v = value.trim().toUpperCase();
    return Arrays.stream(values()).filter(o -> o.name().equals(v) || o.sql.equals(v)).findFirst();
  }

  /**
   * 도구 설명문에 넣는 허용 목록.
   */
  public static String allowedList() {
    return String.join(", ", Arrays.stream(values()).map(o -> o.name() + "(" + o.sql + ")").toList());
  }
}
