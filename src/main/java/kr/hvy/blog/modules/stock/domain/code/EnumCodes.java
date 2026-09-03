package kr.hvy.blog.modules.stock.domain.code;

import java.util.Objects;
import java.util.stream.Stream;
import kr.hvy.common.core.code.base.EnumCode;

/**
 * EnumCode 상수를 code 로 되찾는 헬퍼. JPA 는 AbstractEnumCodeConverter 가 같은 일을 하지만
 * JdbcTemplate 라이터에는 컨버터가 없어 ResultSet 문자열을 여기서 되돌린다.
 */
public final class EnumCodes {

  private EnumCodes() {
  }

  /**
   * code 와 일치하는 상수를 돌려준다. null 은 null, 미지 코드는 IllegalArgumentException (컨버터와 같은 의미).
   */
  public static <E extends Enum<E> & EnumCode<String>> E fromCode(Class<E> type, String code) {
    if (code == null) {
      return null;
    }
    return Stream.of(type.getEnumConstants())
        .filter(e -> Objects.equals(e.getCode(), code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("알 수 없는 코드: " + type.getSimpleName() + "." + code));
  }
}
