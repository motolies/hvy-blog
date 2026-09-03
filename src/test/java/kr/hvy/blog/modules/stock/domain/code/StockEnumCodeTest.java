package kr.hvy.blog.modules.stock.domain.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.client.FinancialKind;
import kr.hvy.blog.modules.stock.client.KsdInfoKind;
import kr.hvy.blog.modules.stock.client.masterfile.MasterFileLayout;
import kr.hvy.blog.modules.stock.client.paginator.DateWindowPaginator;
import kr.hvy.common.core.code.base.EnumCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * stock 모듈 enum 규약: 모든 public enum 은 EnumCode<String> 이고 code 는 상수명과 같다.
 * <p>
 * DB(컨버터·JDBC 라이터·부분 인덱스 WHERE status='RUNNING')·체크포인트 키·REST 경로 변수(Enum.valueOf)가 전부 상수명
 * 기준이라, code 가 상수명과 달라지는 순간 세 곳이 동시에 어긋난다. 그 불변식을 여기서 고정한다.
 */
class StockEnumCodeTest {

  private static final String BASE_PACKAGE = "kr.hvy.blog.modules.stock";

  /** 규약 대상 enum 전체 (새 enum 을 추가하면 여기에도 넣는다 — 스캔 테스트가 누락을 알려준다) */
  private static final List<Class<? extends Enum<?>>> ENUMS = List.of(
      CollectJobType.class, CollectStatus.class, TriggerType.class, CheckpointStatus.class, MarketType.class,
      CorporateActionType.class, CorporateActionSource.class,
      FinancialKind.class, KsdInfoKind.class, MasterFileLayout.class, DateWindowPaginator.Termination.class);

  @Test
  @DisplayName("모든 상수는 code == name() 이고 desc 가 비어 있지 않다")
  void codeEqualsNameAndDescPresent() {
    for (Class<? extends Enum<?>> type : ENUMS) {
      assertThat(EnumCode.class).as("%s 는 EnumCode 를 구현해야 한다", type.getSimpleName()).isAssignableFrom(type);
      for (Enum<?> constant : type.getEnumConstants()) {
        EnumCode<?> enumCode = (EnumCode<?>) constant;
        assertThat(enumCode.getCode()).as("%s.%s code", type.getSimpleName(), constant.name())
            .isEqualTo(constant.name());
        assertThat(enumCode.getDesc()).as("%s.%s desc", type.getSimpleName(), constant.name()).isNotBlank();
      }
    }
  }

  @Test
  @DisplayName("EnumCodes.fromCode 는 code 로 상수를 되찾고, null 은 null, 미지 코드는 IllegalArgumentException")
  void fromCodeRoundTrip() {
    for (Class<? extends Enum<?>> type : ENUMS) {
      for (Enum<?> constant : type.getEnumConstants()) {
        assertThat(fromCode(type, ((EnumCode<?>) constant).getCode().toString())).isSameAs(constant);
      }
      assertThat(fromCode(type, null)).isNull();
      assertThatThrownBy(() -> fromCode(type, "__UNKNOWN__"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(type.getSimpleName());
    }
  }

  @Test
  @DisplayName("stock 패키지의 public enum 은 빠짐없이 목록에 있고 전부 EnumCode 다 (새 enum 이 규약을 건너뛰지 못하게)")
  void everyPublicEnumInModuleIsCovered() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(Enum.class));

    List<String> publicEnums = new ArrayList<>();
    List<String> notEnumCode = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
      Class<?> type = Class.forName(definition.getBeanClassName());
      if (!type.isEnum() || !Modifier.isPublic(type.getModifiers())) {
        continue; // KisApiClient.Outcome 같은 private 제어용 enum 은 대상이 아니다
      }
      publicEnums.add(type.getName());
      if (!EnumCode.class.isAssignableFrom(type)) {
        notEnumCode.add(type.getName());
      }
    }

    assertThat(notEnumCode).as("EnumCode 를 구현하지 않은 public enum").isEmpty();
    assertThat(publicEnums).as("ENUMS 목록과 실제 public enum 이 다르다")
        .containsExactlyInAnyOrderElementsOf(ENUMS.stream().map(Class::getName).toList());
  }

  /**
   * 와일드카드 타입으로는 제네릭 경계를 만족시킬 수 없어 raw 타입으로 호출한다.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Enum<?> fromCode(Class<?> type, String code) {
    return EnumCodes.fromCode((Class) type, code);
  }
}
