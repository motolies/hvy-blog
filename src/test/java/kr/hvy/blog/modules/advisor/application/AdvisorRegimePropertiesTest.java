package kr.hvy.blog.modules.advisor.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
