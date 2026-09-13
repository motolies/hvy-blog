package kr.hvy.blog.modules.advisor.repository.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.service.AdvisorJson;
import org.springframework.jdbc.core.SqlParameterValue;
import tools.jackson.core.type.TypeReference;

/**
 * advisor JDBC 공통: JSONB 바인딩(Types.OTHER)·시각 변환·JSON 컬럼 읽기.
 * <p>
 * PostgreSQL 드라이버는 Instant 를 직접 받지 않으므로 UTC OffsetDateTime 으로 바꿔 timestamptz 에 넣는다.
 */
final class AdvisorJdbc {

  private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP = new TypeReference<>() {
  };
  private static final TypeReference<List<Long>> LIST_OF_LONG = new TypeReference<>() {
  };

  private AdvisorJdbc() {
  }

  /** 객체를 JSON 으로 직렬화해 jsonb 파라미터로 만든다 (null 은 SQL NULL) */
  static SqlParameterValue jsonb(Object value) {
    return new SqlParameterValue(Types.OTHER, value == null ? null : AdvisorJson.write(value));
  }

  /** 이미 JSON 문자열인 값을 jsonb 파라미터로 만든다 */
  static SqlParameterValue jsonbRaw(String json) {
    return new SqlParameterValue(Types.OTHER, json);
  }

  static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static Double nullableDouble(ResultSet rs, String column) throws SQLException {
    double value = rs.getDouble(column);
    return rs.wasNull() ? null : value;
  }

  static Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
    boolean value = rs.getBoolean(column);
    return rs.wasNull() ? null : value;
  }

  static Map<String, Object> jsonMap(ResultSet rs, String column) throws SQLException {
    return AdvisorJson.readMap(rs.getString(column));
  }

  static List<Map<String, Object>> jsonListOfMap(ResultSet rs, String column) throws SQLException {
    String json = rs.getString(column);
    return json == null || json.isBlank() ? List.of() : AdvisorJson.MAPPER.readValue(json, LIST_OF_MAP);
  }

  static List<Long> jsonListOfLong(ResultSet rs, String column) throws SQLException {
    String json = rs.getString(column);
    return json == null || json.isBlank() ? List.of() : AdvisorJson.MAPPER.readValue(json, LIST_OF_LONG);
  }

  static <T extends Enum<T>> T enumOrNull(ResultSet rs, String column, Class<T> type) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : Enum.valueOf(type, value);
  }
}
