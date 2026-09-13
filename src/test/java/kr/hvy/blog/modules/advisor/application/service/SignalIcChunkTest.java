package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IC 월 청크 분할 규칙(순수). IC_BACKFILL 과 증분이 같은 헬퍼를 쓰므로 경계가 여기서 고정된다.
 */
class SignalIcChunkTest {

  @Test
  @DisplayName("월 중간 시작: 다음 달 같은 날 전날까지가 한 청크, 마지막은 to 로 절단")
  void midMonthStart() {
    List<LocalDate[]> chunks = SignalIcService.monthlyChunks(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 20));
    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0)).containsExactly(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 14));
    assertThat(chunks.get(1)).containsExactly(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 14));
    assertThat(chunks.get(2)).containsExactly(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 20));
  }

  @Test
  @DisplayName("월말 시작: java.time 의 plusMonths 절단 규칙을 따른다 (1/31 → 2/27 까지)")
  void monthEndStartFollowsJavaTimeClamp() {
    List<LocalDate[]> chunks = SignalIcService.monthlyChunks(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 5));
    assertThat(chunks.get(0)).containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27));
    assertThat(chunks.get(1)).containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 5));
    assertThat(chunks).hasSize(2);
  }

  @Test
  @DisplayName("하루짜리 범위는 청크 1개, from > to 는 빈 목록")
  void singleDayAndEmpty() {
    LocalDate d = LocalDate.of(2026, 9, 4);
    assertThat(SignalIcService.monthlyChunks(d, d)).hasSize(1);
    assertThat(SignalIcService.monthlyChunks(d, d).getFirst()).containsExactly(d, d);
    assertThat(SignalIcService.monthlyChunks(d.plusDays(1), d)).isEmpty();
  }

  @Test
  @DisplayName("청크 단계 이름은 시작일의 연-월")
  void chunkLabel() {
    assertThat(SignalIcService.chunkLabel(LocalDate.of(2026, 8, 30))).isEqualTo("IC:2026-08");
    assertThat(SignalIcService.chunkLabel(LocalDate.of(2020, 1, 1))).isEqualTo("IC:2020-01");
  }
}
