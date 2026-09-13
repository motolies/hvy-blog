package kr.hvy.blog.modules.advisor.application.slack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 고정폭 표의 한글 2칸 폭 계산·패딩·절단.
 */
class SlackWidthTest {

  @Test
  @DisplayName("한글·한자·전각은 2칸, 영숫자는 1칸, 결합 문자는 0칸")
  void width() {
    assertThat(SlackWidth.width("삼성전자")).isEqualTo(8);
    assertThat(SlackWidth.width("SK하이닉스")).isEqualTo(10);
    assertThat(SlackWidth.width("005930")).isEqualTo(6);
    assertThat(SlackWidth.width("漢字ｶﾅ")).isEqualTo(4 + 2);
    assertThat(SlackWidth.width("é")).isEqualTo(1);
    assertThat(SlackWidth.width(null)).isZero();
  }

  @Test
  @DisplayName("패딩은 표시 폭 기준으로 채우고, 넘치면 마지막 칸을 …로 바꿔 자른다")
  void padAndAbbreviate() {
    assertThat(SlackWidth.padRight("삼성전자", 10)).isEqualTo("삼성전자  ");
    assertThat(SlackWidth.padRight("NVDA", 10)).isEqualTo("NVDA      ");
    assertThat(SlackWidth.width(SlackWidth.padRight("SK하이닉스", 14))).isEqualTo(14);
    assertThat(SlackWidth.abbreviate("LG에너지솔루션", 14)).isEqualTo("LG에너지솔루션");
    assertThat(SlackWidth.abbreviate("LG에너지솔루션우선주", 14)).isEqualTo("LG에너지솔루…");
    assertThat(SlackWidth.width(SlackWidth.padRight("LG에너지솔루션우선주", 14))).isEqualTo(14);
    assertThat(SlackWidth.abbreviate("가나다", 4)).as("전각 경계에서 1칸 남으면 … 만").isEqualTo("가…");
    assertThat(SlackWidth.abbreviate(null, 5)).isEmpty();
  }
}
