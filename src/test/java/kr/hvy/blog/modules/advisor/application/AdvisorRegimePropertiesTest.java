package kr.hvy.blog.modules.advisor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * advisor.regime 기동 검증(M6): 사전 등록 정책 표가 잘못된 값으로 조용히 적용되지 않게 기동을 거부한다.
 */
class AdvisorRegimePropertiesTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());

  @Test
  @DisplayName("기본 표는 통과, 확신 상한 범위 밖·백분위 임계 역전·빈 버전·음수 감산은 거부")
  void rejectsInvalidTable() {
    assertThatCode(properties::validateRegime).doesNotThrowAnyException();

    properties.getRegime().getPolicy().getSideways().setConvictionCap(0.95);
    assertThatThrownBy(properties::validateRegime).hasMessageContaining("sideways.conviction-cap");
    properties.getRegime().getPolicy().getSideways().setConvictionCap(0.80);

    properties.getRegime().setVolLowPct(0.9);
    assertThatThrownBy(properties::validateRegime).hasMessageContaining("vol-low-pct");
    properties.getRegime().setVolLowPct(0.3);

    properties.getRegime().getPolicy().getBear().setLongMaxReduction(-1);
    assertThatThrownBy(properties::validateRegime).hasMessageContaining("bear.long-max-reduction");
    properties.getRegime().getPolicy().getBear().setLongMaxReduction(2);

    properties.getRegime().getPolicy().setVersion(" ");
    assertThatThrownBy(properties::validateRegime).hasMessageContaining("version");
  }

  @Test
  @DisplayName("application.yml 의 advisor.theme.names 11개가 숫자·영문 코드 그대로 바인딩된다(2026-10-16 대표 종목으로 확인한 이름)")
  void themeNamesBindFromApplicationYml() throws Exception {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    Binder binder = new Binder(ConfigurationPropertySources.from(sources.getFirst()));
    AdvisorProperties.Theme theme = binder.bind("advisor.theme", Bindable.of(AdvisorProperties.Theme.class)).get();
    assertThat(theme.getNames()).hasSize(11).containsEntry("1", "건설").containsEntry("5", "정보기술").containsEntry("9", "산업재")
        .containsEntry("A", "헬스케어").containsEntry("B", "커뮤니케이션서비스");
    assertThat(theme.getStrongRs20()).isEqualTo(0.02);
  }
}
