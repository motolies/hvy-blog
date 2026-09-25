package kr.hvy.blog.modules.advisor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.AdviceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * advisor.horizons(M5 멀티 호라이즌) 기본값·조회·기동 검증. 채점·IC·가중치가 모두 이 규칙을 따르므로 여기서 고정한다.
 */
class AdvisorHorizonPropertiesTest {

  @Test
  @DisplayName("기본 맵: 5 DAILY·20 H20 학습, 60·180 모니터링. IC 는 전부, 창은 5 → ic.window-days(120)·20 → 480")
  void defaults() {
    AdvisorProperties p = new AdvisorProperties(new MockEnvironment());
    p.validateHorizons();
    assertThat(p.icHorizons()).containsExactly(5, 20, 60, 180);
    assertThat(p.learnHorizons()).containsExactly(5, 20);
    assertThat(p.icWindowDays(5)).isEqualTo(120);
    assertThat(p.icWindowDays(20)).isEqualTo(480);
    assertThat(p.icWindowDays(20) / 20).as("n_eff 게이트 24 와 같은 기준").isEqualTo(p.getIc().getMinNEff());
    p.getIc().setWindowDays(200);
    assertThat(p.icWindowDays(5)).as("5 는 ic.window-days 를 따라간다(하위 호환)").isEqualTo(200);
    assertThat(p.isLearnHorizon(60)).isFalse();
  }

  @Test
  @DisplayName("종류 → 결정 호라이즌: DAILY·MORNING·ADHOC 는 horizon-days, H20·H60·H180 은 맵 키. 채점 호라이즌은 DAILY·MORNING 만 진단 포함, ADHOC 없음")
  void kindHorizons() {
    AdvisorProperties p = new AdvisorProperties(new MockEnvironment());
    assertThat(p.decisionHorizon(AdviceKind.DAILY)).isEqualTo(5);
    assertThat(p.decisionHorizon(AdviceKind.MORNING)).isEqualTo(5);
    assertThat(p.decisionHorizon(AdviceKind.ADHOC)).isEqualTo(5);
    assertThat(p.decisionHorizon(AdviceKind.H20)).isEqualTo(20);
    assertThat(p.decisionHorizon(AdviceKind.H180)).isEqualTo(180);
    assertThat(p.scoreHorizons(AdviceKind.DAILY)).containsExactly(5, 1, 20);
    assertThat(p.scoreHorizons(AdviceKind.MORNING)).containsExactly(5, 1, 20);
    assertThat(p.scoreHorizons(AdviceKind.H20)).containsExactly(20);
    assertThat(p.scoreHorizons(AdviceKind.H60)).containsExactly(60);
    assertThat(p.scoreHorizons(AdviceKind.ADHOC)).isEmpty();

    p.getHorizons().remove(180);
    assertThat(p.horizonOf(AdviceKind.H180)).isEmpty();
    assertThat(p.scoreHorizons(AdviceKind.H180)).isEmpty();
    assertThatThrownBy(() -> p.decisionHorizon(AdviceKind.H180)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("기동 검증: 결정 호라이즌 키가 DAILY 가 아니거나, 종류 중복·MORNING·창 < 호라이즌이면 거부")
  void validation() {
    AdvisorProperties missingDaily = new AdvisorProperties(new MockEnvironment());
    missingDaily.getHorizons().remove(5);
    assertThatThrownBy(missingDaily::validateHorizons).isInstanceOf(IllegalStateException.class).hasMessageContaining("DAILY");

    AdvisorProperties duplicate = new AdvisorProperties(new MockEnvironment());
    duplicate.getHorizons().put(40, new AdvisorProperties.Horizon(AdviceKind.H20, false, null));
    assertThatThrownBy(duplicate::validateHorizons).isInstanceOf(IllegalStateException.class).hasMessageContaining("두 번 이상");

    AdvisorProperties morning = new AdvisorProperties(new MockEnvironment());
    morning.getHorizons().put(3, new AdvisorProperties.Horizon(AdviceKind.MORNING, false, null));
    assertThatThrownBy(morning::validateHorizons).isInstanceOf(IllegalStateException.class).hasMessageContaining("MORNING");

    AdvisorProperties shortWindow = new AdvisorProperties(new MockEnvironment());
    shortWindow.getHorizons().get(20).setIcWindow(10);
    assertThatThrownBy(shortWindow::validateHorizons).isInstanceOf(IllegalStateException.class).hasMessageContaining("ic-window");
  }

  @Test
  @DisplayName("yml 바인딩: 숫자 키 맵이 기본값에 병합되고 kind 는 enum 이름으로 바인딩된다")
  void bindsNumericKeysOntoDefaults() {
    AdvisorProperties p = new AdvisorProperties(new MockEnvironment());
    MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
        "advisor.horizons.20.ic-window", "240",
        "advisor.horizons.20.kind", "H20",
        "advisor.horizons.20.learn", "true",
        "advisor.horizons.60.learn", "true",
        "advisor.horizons.60.kind", "H60"));
    new Binder(source).bind("advisor", Bindable.ofInstance(p));
    p.validateHorizons();
    assertThat(p.icHorizons()).containsExactly(5, 20, 60, 180);
    assertThat(p.icWindowDays(20)).isEqualTo(240);
    assertThat(p.learnHorizons()).isEqualTo(List.of(5, 20, 60));
  }
}
