package kr.hvy.blog.modules.advisor.domain.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지속 기간 버킷 실현 규칙과 무효화 조건의 방향 일관성.
 */
class TrendHorizonTest {

  @Test
  @DisplayName("전환 오프셋 1~5 → WITHIN_5D, 6~20 → ABOUT_20D, 없음·창 밖 → BEYOND_20D")
  void realizedBuckets() {
    assertThat(TrendHorizon.realized(1, 5, 20)).isEqualTo(TrendHorizon.WITHIN_5D);
    assertThat(TrendHorizon.realized(5, 5, 20)).isEqualTo(TrendHorizon.WITHIN_5D);
    assertThat(TrendHorizon.realized(6, 5, 20)).isEqualTo(TrendHorizon.ABOUT_20D);
    assertThat(TrendHorizon.realized(20, 5, 20)).isEqualTo(TrendHorizon.ABOUT_20D);
    assertThat(TrendHorizon.realized(21, 5, 20)).isEqualTo(TrendHorizon.BEYOND_20D);
    assertThat(TrendHorizon.realized(null, 5, 20)).isEqualTo(TrendHorizon.BEYOND_20D);
  }

  @Test
  @DisplayName("강세·보합은 하향 이탈만, 약세는 상향 돌파만 뜻이 있고 NONE 은 어디에나 허용")
  void invalidationConsistency() {
    assertThat(InvalidationType.BELOW_MA20.consistentWith(MarketTrendCode.BULL)).isTrue();
    assertThat(InvalidationType.BELOW_MA60.consistentWith(MarketTrendCode.SIDEWAYS)).isTrue();
    assertThat(InvalidationType.ABOVE_MA20.consistentWith(MarketTrendCode.BULL)).isFalse();
    assertThat(InvalidationType.ABOVE_MA60.consistentWith(MarketTrendCode.BEAR)).isTrue();
    assertThat(InvalidationType.BELOW_MA20.consistentWith(MarketTrendCode.BEAR)).isFalse();
    assertThat(InvalidationType.NONE.consistentWith(MarketTrendCode.BEAR)).isTrue();
    assertThat(InvalidationType.ABOVE_MA20.consistentWith(null)).as("추세 미상이면 검사하지 않는다").isTrue();
  }
}
