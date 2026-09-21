package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteStatus;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.PickNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * recentOutcomes 빈도표 규약: 게이트(min-finalized) 미달이면 empty, 창은 기준일 앞 N 거래일 + 기준일 20:00 KST cutoff,
 * 행은 class × secCons(null 은 "-") 로 n 내림차순 상위 max-rows, se 는 n<2 면 null, underpowered 는 n<30.
 */
class RecentOutcomesServiceTest {

  private final PickNoteRepository notes = mock(PickNoteRepository.class);
  private final TradingCalendar calendar = mock(TradingCalendar.class);
  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final LocalDate base = LocalDate.of(2026, 10, 10);
  private RecentOutcomesService service;

  @BeforeEach
  void setUp() {
    service = new RecentOutcomesService(notes, calendar, properties);
    // 20 거래일 전 = 2026-09-11 이라 가정
    List<LocalDate> previous = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      previous.add(base.minusDays(i));
    }
    previous.set(19, LocalDate.of(2026, 9, 11));
    when(calendar.previousTradingDays(eq(base), anyInt())).thenReturn(previous);
  }

  @Test
  @DisplayName("확정 노트가 min-finalized 미만이면 블록을 만들지 않고, 조회 창은 기준일 앞 N 거래일·기준일 20:00 KST cutoff 다")
  void gateAndWindow() {
    properties.getNote().setMinFinalized(10);
    when(notes.findFinalized(any(), any(), any())).thenReturn(rows(9, PickNoteClass.ON_TRACK, 1, PickNoteStatus.CONFIRMED, 0.01));

    assertThat(service.block(base)).isEmpty();
    verify(notes).findFinalized(LocalDate.of(2026, 9, 11), base, Instant.parse("2026-10-10T11:00:00Z"));
  }

  @Test
  @DisplayName("행은 class × secCons 로 묶어 n 내림차순 상위 max-rows, secCons 없는 노트는 '-', se 는 n<2 면 null, underpowered 는 n<30")
  void buildsRows() {
    properties.getNote().setMinFinalized(10);
    properties.getNote().setMaxRows(3);
    List<PickNoteRow> finalized = new ArrayList<>();
    finalized.addAll(rows(14, PickNoteClass.IDIOSYNCRATIC, 1, PickNoteStatus.REFUTED, -0.02));   // 14건, 확정률 0
    finalized.addAll(rows(30, PickNoteClass.ON_TRACK, 1, PickNoteStatus.CONFIRMED, 0.01));     // 30건 → underpowered false
    finalized.addAll(rows(3, PickNoteClass.MARKET_DRAG, 0, PickNoteStatus.CONFIRMED, 0.005));
    finalized.add(row(PickNoteClass.OVERSHOOT, null, PickNoteStatus.REFUTED, -0.03));          // secCons 없음 → "-", n=1 → se null
    finalized.add(row(PickNoteClass.ON_TRACK, 1, PickNoteStatus.REFUTED, 0.03));               // ON_TRACK×1 → 31건, 확정률 30/31
    when(notes.findFinalized(any(), any(), any())).thenReturn(finalized);

    Map<String, Object> block = service.block(base).orElseThrow();

    assertThat(block).containsEntry("windowTradingDays", 20).containsEntry("finalizedAsOf", "2026-10-10")
        .containsEntry("columns", List.of("class", "secCons", "n", "confirmRate", "meanFinalExcess", "se", "underpowered"));
    @SuppressWarnings("unchecked")
    List<List<Object>> rows = (List<List<Object>>) block.get("rows");
    assertThat(rows).as("5 그룹 중 n 상위 3행").hasSize(3);
    assertThat(rows.get(0)).startsWith("ON_TRACK", 1, 31).satisfies(r -> {
      assertThat((Double) r.get(3)).isCloseTo(30.0 / 31, org.assertj.core.data.Offset.offset(1e-4));
      assertThat((Double) r.get(4)).as("평균 (30×0.01 + 0.03)/31").isCloseTo(0.0106, org.assertj.core.data.Offset.offset(1e-4));
      assertThat((Double) r.get(5)).as("se > 0").isPositive();
      assertThat(r.get(6)).as("n ≥ 30").isEqualTo(false);
    });
    assertThat(rows.get(1)).containsExactly("IDIOSYNCRATIC", 1, 14, 0.0, -0.02, 0.0, true);
    assertThat(rows.get(2)).startsWith("MARKET_DRAG", 0, 3).endsWith(true);
    assertThat(AdvisorJson.write(block)).as("LLM 회고 문장은 어떤 경로로도 실리지 않는다").doesNotContain("deviation").doesNotContain("why").doesNotContain("hypothesis");
  }

  @Test
  @DisplayName("secCons 없는 그룹은 '-' 로 표기되고 단일 표본의 se 는 null 이다 (순수 함수)")
  void unknownSecConsAndSingleSample() {
    Map<String, Object> block = RecentOutcomesService.build(List.of(row(PickNoteClass.OVERSHOOT, null, PickNoteStatus.REFUTED, -0.03)), 20, base, 5);
    @SuppressWarnings("unchecked")
    List<List<Object>> rows = (List<List<Object>>) block.get("rows");
    assertThat(rows).containsExactly(java.util.Arrays.asList("OVERSHOOT", "-", 1, 0.0, -0.03, null, true));
    assertThat(RecentOutcomesService.rowCount(block)).isEqualTo(1);
    assertThat(RecentOutcomesService.rowCount(null)).isZero();
  }

  private static List<PickNoteRow> rows(int n, PickNoteClass cls, Integer secCons, PickNoteStatus status, double finalExcess) {
    List<PickNoteRow> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      list.add(row(cls, secCons, status, finalExcess));
    }
    return list;
  }

  private static PickNoteRow row(PickNoteClass cls, Integer secCons, PickNoteStatus status, double finalExcess) {
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("excessBasis", "OPEN");
    if (secCons != null) {
      tags.put("secCons", secCons);
    }
    return PickNoteRow.builder().adviceId(1L).checkId(1L).ticker("T00").baseDate(LocalDate.of(2026, 10, 1)).notedAt(Instant.parse("2026-10-02T03:00:00Z"))
        .direction(PickDirection.LONG).conviction(0.7).noteClass(cls).deviation("12:00 편차 문장").why("왜 문장").hypothesis("가설 문장").tags(tags)
        .status(status).finalExcess(finalExcess).finalizedAt(Instant.parse("2026-10-09T11:00:00Z")).build();
  }
}
