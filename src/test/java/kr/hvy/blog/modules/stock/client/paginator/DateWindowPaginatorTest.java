package kr.hvy.blog.modules.stock.client.paginator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DateWindowPaginatorTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);
  private static final LocalDate TARGET = LocalDate.of(2015, 1, 1);

  private final DateWindowPaginator paginator = new DateWindowPaginator();
  private final List<LocalDate> checkpoints = new ArrayList<>();

  /** 영업일(주말 제외) 캔들을 dataFrom 이후만 돌려주는 가짜 KIS. 최신 100건만 준다 */
  private DateWindowPaginator.WindowFetcher<LocalDate> fakeKis(LocalDate dataFrom, boolean repeatAtLimit) {
    return (from, to) -> {
      List<LocalDate> page = new ArrayList<>();
      LocalDate cursor = to;
      while (!cursor.isBefore(from) && page.size() < 100) {
        if (cursor.getDayOfWeek().getValue() <= 5 && !cursor.isBefore(dataFrom)) {
          page.add(cursor);
        }
        cursor = cursor.minusDays(1);
      }
      if (page.isEmpty() && repeatAtLimit) {
        // 일부 API 는 한계 이전 구간을 요청해도 가장 오래된 데이터를 반복해 준다
        LocalDate d = dataFrom;
        while (page.size() < 5) {
          if (d.getDayOfWeek().getValue() <= 5) {
            page.add(d);
          }
          d = d.plusDays(1);
        }
      }
      return page;
    };
  }

  private DateWindowPaginator.Outcome run(DateWindowPaginator.WindowFetcher<LocalDate> fetcher, LocalDate floor, int maxWindows) {
    return paginator.paginateBackward(TODAY, TARGET, floor, 140, maxWindows, fetcher, Function.identity(),
        List::size, (next, earliest, latest, windows) -> checkpoints.add(next));
  }

  @Test
  @DisplayName("(b) 목표 시작일까지 내려가면 REACHED_TARGET, 윈도우마다 체크포인트가 기록된다")
  void reachesTarget() {
    DateWindowPaginator.Outcome outcome = run(fakeKis(LocalDate.of(2000, 1, 1), false), null, 60);

    assertThat(outcome.termination()).isEqualTo(DateWindowPaginator.Termination.REACHED_TARGET);
    assertThat(outcome.earliest()).isBeforeOrEqualTo(TARGET);
    assertThat(outcome.latest()).isEqualTo(TODAY);
    assertThat(outcome.windows()).isBetween(29, 33);
    assertThat(outcome.rows()).isGreaterThan(2_800);
    assertThat(checkpoints).hasSize(outcome.windows());
    assertThat(checkpoints).isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  @Test
  @DisplayName("(a) 소급 한계에서 빈 응답이 오면 EXHAUSTED, 상장일이 알려져 있으면 REACHED_TARGET")
  void exhaustedOnEmpty() {
    LocalDate limit = LocalDate.of(2020, 3, 2);
    DateWindowPaginator.Outcome exhausted = run(fakeKis(limit, false), null, 60);
    assertThat(exhausted.termination()).isEqualTo(DateWindowPaginator.Termination.EXHAUSTED);
    assertThat(exhausted.earliest()).isEqualTo(limit);

    checkpoints.clear();
    DateWindowPaginator.Outcome listed = run(fakeKis(limit, false), limit, 60);
    assertThat(listed.termination()).isEqualTo(DateWindowPaginator.Termination.REACHED_TARGET);
  }

  @Test
  @DisplayName("(c) 같은 최소 일자가 반복되면 EXHAUSTED 로 끊어 무한 루프를 막는다")
  void exhaustedOnRepeat() {
    DateWindowPaginator.Outcome outcome = run(fakeKis(LocalDate.of(2021, 1, 4), true), null, 60);

    assertThat(outcome.termination()).isEqualTo(DateWindowPaginator.Termination.EXHAUSTED);
    assertThat(outcome.earliest()).isEqualTo(LocalDate.of(2021, 1, 4));
  }

  @Test
  @DisplayName("(d) 윈도우 상한을 넘으면 WINDOW_LIMIT, 데이터가 전혀 없으면 EMPTY")
  void windowLimitAndEmpty() {
    DateWindowPaginator.Outcome limited = run(fakeKis(LocalDate.of(2000, 1, 1), false), null, 3);
    assertThat(limited.termination()).isEqualTo(DateWindowPaginator.Termination.WINDOW_LIMIT);
    assertThat(limited.windows()).isEqualTo(3);

    DateWindowPaginator.Outcome empty = run((from, to) -> List.of(), null, 10);
    assertThat(empty.termination()).isEqualTo(DateWindowPaginator.Termination.EMPTY);
    assertThat(empty.windows()).isEqualTo(1);
    assertThat(empty.rows()).isZero();
  }
}
