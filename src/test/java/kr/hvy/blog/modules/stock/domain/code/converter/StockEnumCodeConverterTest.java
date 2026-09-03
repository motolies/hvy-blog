package kr.hvy.blog.modules.stock.domain.code.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.common.core.code.base.AbstractEnumCodeConverter;
import kr.hvy.common.core.code.base.EnumCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JPA 컨버터 5개의 왕복. 생성자가 protected 라 같은 패키지에 둔다.
 * 저장값이 상수명과 같아야 기존 @Enumerated(STRING) 시절 데이터·부분 인덱스(status='RUNNING')와 호환된다.
 */
class StockEnumCodeConverterTest {

  private record Case<E extends Enum<E> & EnumCode<String>>(AbstractEnumCodeConverter<E, String> converter,
                                                            Class<E> type) {

  }

  private static final List<Case<?>> CASES = List.of(
      new Case<>(new CollectJobTypeConverter(), CollectJobType.class),
      new Case<>(new TriggerTypeConverter(), TriggerType.class),
      new Case<>(new CollectStatusConverter(), CollectStatus.class),
      new Case<>(new CheckpointStatusConverter(), CheckpointStatus.class),
      new Case<>(new MarketTypeConverter(), MarketType.class));

  @Test
  @DisplayName("DB 컬럼값은 상수명이고, 그 값으로 다시 같은 상수를 얻는다")
  void roundTrip() {
    for (Case<?> c : CASES) {
      roundTrip(c);
    }
  }

  @Test
  @DisplayName("null 은 양방향 null, 미지 코드는 IllegalArgumentException")
  void nullAndUnknown() {
    for (Case<?> c : CASES) {
      assertThat(c.converter().convertToDatabaseColumn(null)).isNull();
      assertThat(c.converter().convertToEntityAttribute(null)).isNull();
      assertThatThrownBy(() -> c.converter().convertToEntityAttribute("__UNKNOWN__"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static <E extends Enum<E> & EnumCode<String>> void roundTrip(Case<E> c) {
    for (E constant : c.type().getEnumConstants()) {
      String column = c.converter().convertToDatabaseColumn(constant);
      assertThat(column).as("%s.%s 저장값", c.type().getSimpleName(), constant.name()).isEqualTo(constant.name());
      assertThat(c.converter().convertToEntityAttribute(column)).isSameAs(constant);
    }
  }
}
