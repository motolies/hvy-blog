package kr.hvy.blog.modules.advisor.domain.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.common.core.code.base.EnumCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * advisor 모듈 enum 규약: 모든 public enum 은 EnumCode<String> 이고 code 는 상수명과 같다 (StockEnumCodeTest 와 같은 불변식).
 * DB 컬럼·부분 인덱스(WHERE status='RUNNING')·REST 경로 변수(Enum.valueOf)가 전부 상수명 기준이다.
 */
class AdvisorEnumCodeTest {

  private static final String BASE_PACKAGE = "kr.hvy.blog.modules.advisor";

  @Test
  @DisplayName("advisor 패키지의 public enum 은 전부 EnumCode 이고 code == name(), desc 는 비어 있지 않다")
  void everyPublicEnumFollowsConvention() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(Enum.class));

    List<String> publicEnums = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
      Class<?> type = Class.forName(definition.getBeanClassName());
      if (!type.isEnum() || !Modifier.isPublic(type.getModifiers())) {
        continue;
      }
      publicEnums.add(type.getSimpleName());
      assertThat(EnumCode.class).as("%s 는 EnumCode 를 구현해야 한다", type.getSimpleName()).isAssignableFrom(type);
      for (Object constant : type.getEnumConstants()) {
        Enum<?> e = (Enum<?>) constant;
        EnumCode<?> enumCode = (EnumCode<?>) constant;
        assertThat(enumCode.getCode()).as("%s.%s code", type.getSimpleName(), e.name()).isEqualTo(e.name());
        assertThat(enumCode.getDesc()).as("%s.%s desc", type.getSimpleName(), e.name()).isNotBlank();
      }
    }
    assertThat(publicEnums).as("스캔된 enum").contains("AdvisorJobType", "AdvisorStatus", "AdvisorTriggerType", "AdviceVariant",
        "PickDirection", "ScoreStage", "ScoreStatus", "LessonStatus");
  }
}
