package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import kr.hvy.blog.modules.advisor.domain.code.VolRegimeCode;
import kr.hvy.blog.modules.advisor.domain.model.MarketRegime.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 사전 등록 정책 표(regime-policy-v1)의 수치를 고정한다. 이 테스트가 깨지면 표가 바뀐 것이다 — 운영 문서 §1.7 과 advisor.regime.policy.version 을 함께 올린다.
 */
class RegimePolicyTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final RegimePolicy policy = new RegimePolicy(properties);

  @Test
  @DisplayName("표 v1 (pick-min 3·pick-max 10): BULL 기존, SIDEWAYS 확신 0.80, BEAR LONG 8·확신 0.70·AVOID 4")
  void tableV1() {
    assertThat(policy.limits(MarketTrendCode.BULL, VolRegimeCode.NORMAL)).isEqualTo(new Policy("regime-policy-v1", 10, null, 2));
    assertThat(policy.limits(MarketTrendCode.SIDEWAYS, VolRegimeCode.LOW)).isEqualTo(new Policy("regime-policy-v1", 10, 0.80, 2));
    assertThat(policy.limits(MarketTrendCode.BEAR, VolRegimeCode.NORMAL)).isEqualTo(new Policy("regime-policy-v1", 8, 0.70, 4));
  }

  @Test
  @DisplayName("변동성 HIGH 는 확신 상한 −0.05: BEAR 0.65, SIDEWAYS 0.75, 상한 없던 BULL 은 0.90 − 0.05 = 0.85 — UNKNOWN·LOW 는 가산 없음")
  void volHighPenalty() {
    assertThat(policy.limits(MarketTrendCode.BEAR, VolRegimeCode.HIGH).convictionCap()).isEqualTo(0.65);
    assertThat(policy.limits(MarketTrendCode.SIDEWAYS, VolRegimeCode.HIGH).convictionCap()).isEqualTo(0.75);
    assertThat(policy.limits(MarketTrendCode.BULL, VolRegimeCode.HIGH).convictionCap()).isEqualTo(0.85);
    assertThat(policy.limits(MarketTrendCode.BULL, VolRegimeCode.UNKNOWN).convictionCap()).isNull();
    assertThat(policy.limits(MarketTrendCode.BEAR, VolRegimeCode.UNKNOWN).convictionCap()).isEqualTo(0.70);
  }

  @Test
  @DisplayName("LONG 상한은 pick-min 아래로 내려가지 않는다, 추세가 없으면 정책도 없다(null)")
  void respectsPickMinAndMissingTrend() {
    properties.setPickMax(4);
    assertThat(policy.limits(MarketTrendCode.BEAR, VolRegimeCode.HIGH).longMax()).as("max(3, 4−2)").isEqualTo(3);
    properties.setPickMax(10);
    properties.getRegime().getPolicy().getBear().setLongMaxReduction(20);
    assertThat(policy.limits(MarketTrendCode.BEAR, VolRegimeCode.NORMAL).longMax()).isEqualTo(properties.getPickMin());
    assertThat(policy.limits(null, VolRegimeCode.HIGH)).isNull();
  }

  @Test
  @DisplayName("확신 상한은 허용 이산값 격자로 내린다 (0.67 → 0.65, 0.5 → 0.55 하한, 0.70−0.05 부동소수 → 0.65)")
  void snapsToAllowedGrid() {
    assertThat(RegimePolicy.snapDown(0.67)).isEqualTo(0.65);
    assertThat(RegimePolicy.snapDown(0.5)).isEqualTo(0.55);
    assertThat(RegimePolicy.snapDown(0.70 - 0.05)).isEqualTo(0.65);
    assertThat(RegimePolicy.snapDown(0.95)).isEqualTo(0.90);
  }

  @Test
  @DisplayName("정책 표 버전이 한도에 실린다 — 수치를 바꾸면 버전을 올려 판단마다 구분한다")
  void versionTravelsWithLimits() {
    properties.getRegime().getPolicy().setVersion("regime-policy-v2");
    assertThat(policy.limits(MarketTrendCode.BULL, VolRegimeCode.NORMAL).version()).isEqualTo("regime-policy-v2");
  }
}
