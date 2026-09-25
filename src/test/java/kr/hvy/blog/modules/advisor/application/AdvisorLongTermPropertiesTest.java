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
 * advisor.long-term·advisor.h20 기동 검증(M7·M8)과 application.yml 바인딩 — 장기 가중치 맵의 대문자·밑줄 키(MOM_12_1)가 완화 바인딩에 뭉개지지 않는지.
 */
class AdvisorLongTermPropertiesTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());

  @Test
  @DisplayName("장기 기본값은 통과, 장기 팩터가 아닌 키·음수·합 0·커버리지 범위 밖·픽 수 > 후보 수·결산 구분 오타는 거부")
  void rejectsInvalidLongTerm() {
    assertThatCode(properties::validateLongTerm).doesNotThrowAnyException();
    assertThat(properties.getLongTerm().getWeights()).containsOnlyKeys("MOM_12_1", "QUALITY_ROE", "QUALITY_DEBT", "OP_GROWTH", "LOW_VOL_60");

    properties.getLongTerm().getWeights().put("MOM_20D", 0.1);
    assertThatThrownBy(properties::validateLongTerm).hasMessageContaining("MOM_20D");
    properties.getLongTerm().getWeights().remove("MOM_20D");

    properties.getLongTerm().getWeights().put("OP_GROWTH", -0.1);
    assertThatThrownBy(properties::validateLongTerm).hasMessageContaining("OP_GROWTH");
    properties.getLongTerm().getWeights().replaceAll((k, v) -> 0.0);
    assertThatThrownBy(properties::validateLongTerm).hasMessageContaining("합이 0");
    properties.getLongTerm().setWeights(AdvisorProperties.LongTerm.defaultLongTermWeights());

    properties.getLongTerm().setMinCoverage(0);
    assertThatThrownBy(properties::validateLongTerm).hasMessageContaining("min-coverage");
    properties.getLongTerm().setMinCoverage(0.6);

    properties.getLongTerm().setPickCount(40);
    assertThatThrownBy(properties::validateLongTerm).hasMessageContaining("pick-count");
    properties.getLongTerm().setPickCount(10);

    properties.getLongTerm().setFinancialPeriodType("A");
    assertThatThrownBy(properties::validateLongTerm).hasMessageContaining("financial-period-type");
  }

  @Test
  @DisplayName("H20 픽 범위: 기본 3~8 통과, min > max·36 초과는 거부")
  void rejectsInvalidH20() {
    assertThatCode(properties::validateH20).doesNotThrowAnyException();
    properties.getH20().setPickMin(9);
    assertThatThrownBy(properties::validateH20).hasMessageContaining("advisor.h20");
    properties.getH20().setPickMin(3);
    properties.getH20().setPickMax(40);
    assertThatThrownBy(properties::validateH20).hasMessageContaining("36");
  }

  @Test
  @DisplayName("application.yml 의 advisor.long-term·h20 이 그대로 바인딩된다 — 가중치 키 5개가 원형 그대로, 기동 검증 통과")
  void bindsFromApplicationYml() throws Exception {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    Binder binder = new Binder(ConfigurationPropertySources.from(sources.getFirst()));
    AdvisorProperties.LongTerm lt = binder.bind("advisor.long-term", Bindable.of(AdvisorProperties.LongTerm.class)).get();
    assertThat(lt.getWeights()).containsEntry("MOM_12_1", 0.30).containsEntry("QUALITY_ROE", 0.20).containsEntry("QUALITY_DEBT", 0.15)
        .containsEntry("OP_GROWTH", 0.15).containsEntry("LOW_VOL_60", 0.20).hasSize(5);
    assertThat(lt.getFinancialPeriodType()).isEqualTo("Y");
    properties.setLongTerm(lt);
    assertThatCode(properties::validateLongTerm).doesNotThrowAnyException();
    AdvisorProperties.H20 h20 = binder.bind("advisor.h20", Bindable.of(AdvisorProperties.H20.class)).get();
    assertThat(h20.getPickMin()).isEqualTo(3);
    assertThat(h20.getPickMax()).isEqualTo(8);
  }
}
