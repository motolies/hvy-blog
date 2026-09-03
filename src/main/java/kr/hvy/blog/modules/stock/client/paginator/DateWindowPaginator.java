package kr.hvy.blog.modules.stock.client.paginator;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 날짜 윈도우를 과거로 밀며 반복 호출하는 페이저 (FH* 기간별 시세 계열).
 * <p>
 * KIS 기간별 시세는 [시작일, 종료일] 안에서 최신 100건만 준다. 종료일을 "직전 응답의 최소 일자 - 1일"로 옮기며
 * 목표 시작일까지 내려간다. 종료 조건 4개:
 * <ol>
 *   <li>응답이 비어 있음 → 더 과거 데이터 없음(EXHAUSTED). 아직 한 건도 못 받았으면 EMPTY</li>
 *   <li>최소 일자 ≤ 목표 시작일(또는 상장일) → REACHED_TARGET</li>
 *   <li>최소 일자가 직전 윈도우와 같음 → KIS 가 소급 한계에서 같은 데이터를 반복 → EXHAUSTED</li>
 *   <li>윈도우 수 상한 초과 → WINDOW_LIMIT (무한 루프 안전장치)</li>
 * </ol>
 * 윈도우마다 sink 로 저장하고 checkpointer 를 호출하므로 중간에 죽어도 다음 커서부터 재개된다.
 */
@Slf4j
@Component
public class DateWindowPaginator {

  /** 한 윈도우를 조회한다 (from ≤ to) */
  @FunctionalInterface
  public interface WindowFetcher<C> {

    List<C> fetch(LocalDate from, LocalDate to);
  }

  /** 한 윈도우를 저장하고 실제 변경 행 수를 돌려준다 */
  @FunctionalInterface
  public interface WindowSink<C> {

    int store(List<C> rows);
  }

  /** 윈도우 저장 직후 재개 지점을 기록한다 */
  @FunctionalInterface
  public interface Checkpointer {

    void advanced(LocalDate nextCursor, LocalDate earliest, LocalDate latest, int windows);
  }

  @Getter
  @AllArgsConstructor
  public enum Termination implements EnumCode<String> {
    REACHED_TARGET("REACHED_TARGET", "목표 시작일 도달"),
    EXHAUSTED("EXHAUSTED", "소급 한계 (같은 최소일자 반복)"),
    EMPTY("EMPTY", "빈 응답"),
    WINDOW_LIMIT("WINDOW_LIMIT", "윈도우 상한 초과");

    private final String code;
    private final String desc;

    /** 데이터를 더 요구할 수 없는 정상 종료(목표 도달 또는 소급 한계)인지 */
    public boolean isSettled() {
      return this == REACHED_TARGET || this == EXHAUSTED || this == EMPTY;
    }
  }

  /**
   * 결과 요약.
   *
   * @param earliest 이번 실행에서 확보한 가장 과거 일자 (없으면 null)
   * @param latest   가장 최근 일자
   * @param windows  호출한 윈도우 수
   * @param rows     저장된(변경된) 행 수
   */
  public record Outcome(Termination termination, LocalDate earliest, LocalDate latest, int windows, int rows) {
  }

  /**
   * 과거 방향 페이징.
   *
   * @param cursor      첫 윈도우의 종료일 (보통 오늘 또는 체크포인트 cursor_date)
   * @param targetStart 목표 시작일 (이 날짜 이하를 받으면 완료)
   * @param floorDate   상장일 등 데이터가 존재할 수 없는 하한 (모르면 null)
   * @param windowDays  윈도우 폭(캘린더일). 일봉 100건 ≈ 140일
   * @param maxWindows  윈도우 수 상한
   * @param dateOf      행에서 거래일을 꺼내는 함수 (null 이면 해당 행 무시)
   */
  public <C> Outcome paginateBackward(LocalDate cursor, LocalDate targetStart, LocalDate floorDate,
      int windowDays, int maxWindows, WindowFetcher<C> fetcher, Function<C, LocalDate> dateOf,
      WindowSink<C> sink, Checkpointer checkpointer) {
    LocalDate earliest = null;
    LocalDate latest = null;
    LocalDate previousMin = null;
    int windows = 0;
    int rows = 0;

    while (true) {
      if (windows >= maxWindows) {
        return new Outcome(Termination.WINDOW_LIMIT, earliest, latest, windows, rows);
      }
      LocalDate from = cursor.minusDays(windowDays - 1L);
      List<C> page = fetcher.fetch(from, cursor);
      windows++;

      LocalDate min = null;
      LocalDate max = null;
      List<C> valid = page == null ? List.of() : page.stream().filter(row -> dateOf.apply(row) != null).toList();
      for (C row : valid) {
        LocalDate date = dateOf.apply(row);
        if (min == null || date.isBefore(min)) {
          min = date;
        }
        if (max == null || date.isAfter(max)) {
          max = date;
        }
      }

      if (valid.isEmpty()) {
        Termination termination = earliest == null ? Termination.EMPTY
            : (floorDate != null && !earliest.isAfter(floorDate.plusDays(7)) ? Termination.REACHED_TARGET : Termination.EXHAUSTED);
        return new Outcome(termination, earliest, latest, windows, rows);
      }

      rows += sink.store(valid);
      if (earliest == null || min.isBefore(earliest)) {
        earliest = min;
      }
      if (latest == null || max.isAfter(latest)) {
        latest = max;
      }
      LocalDate nextCursor = min.minusDays(1);
      checkpointer.advanced(nextCursor, earliest, latest, windows);

      if (previousMin != null && !min.isBefore(previousMin)) {
        log.debug("동일 최소 일자 반복 → 소급 한계: min={}, windows={}", min, windows);
        return new Outcome(Termination.EXHAUSTED, earliest, latest, windows, rows);
      }
      if (!min.isAfter(targetStart) || (floorDate != null && !min.isAfter(floorDate))) {
        return new Outcome(Termination.REACHED_TARGET, earliest, latest, windows, rows);
      }
      previousMin = min;
      cursor = nextCursor;
    }
  }
}
