package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.client.llm.LessonProposalResponse;
import kr.hvy.blog.modules.advisor.domain.code.LessonScope;
import kr.hvy.blog.modules.advisor.domain.code.LessonStatus;
import kr.hvy.blog.modules.advisor.domain.model.LessonRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.LessonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * 교훈 규율: 셀 집계(n 게이트·버킷), 제안 거부 사유(형식·근거·티커·중복), 활성 슬롯, 폐기 규칙(개선 없음)과 후보 승격.
 */
class LessonServiceTest {

  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final LessonRepository lessons = mock(LessonRepository.class);
  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private LessonService service;

  @BeforeEach
  void setUp() {
    properties.getLesson().setMinCellSamples(3);
    service = new LessonService(jdbc, lessons, properties);
    when(lessons.findByStatus(any())).thenReturn(List.of());
  }

  @Test
  @DisplayName("셀 집계: 국면·(국면×섹터)·(국면×시그널 HIGH/LOW) 셀을 만들고 n 미만은 버린다")
  void aggregatesCells() {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      rows.add(pickRow("RISK_ON", "G2510", "{\"TV_SURGE\":{\"pct\":0.95,\"w\":0.1},\"MOM_20D\":{\"pct\":0.5,\"w\":0.12}}", 0.01 * (i + 1), LocalDate.of(2026, 9, 1 + i)));
    }
    rows.add(pickRow("RISK_OFF", "G3020", "{\"TV_SURGE\":{\"pct\":0.1,\"w\":0.1}}", -0.02, LocalDate.of(2026, 9, 8)));
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(rows);

    List<LessonService.Cell> cells = service.cells(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30));

    assertThat(cells).extracting(c -> c.regime() + "/" + c.signal() + "/" + c.bucket() + "/" + c.sector())
        .containsExactlyInAnyOrder("RISK_ON/null/null/null", "RISK_ON/null/null/G2510", "RISK_ON/TV_SURGE/HIGH/null");
    LessonService.Cell regime = cells.stream().filter(c -> c.signal() == null && c.sector() == null).findFirst().orElseThrow();
    assertThat(regime.n()).isEqualTo(4);
    assertThat(regime.meanExcess()).isCloseTo(0.025, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(regime.tStat()).isPositive();
    assertThat(regime.nDays()).as("4픽이 서로 다른 4일").isEqualTo(4);
    assertThat(regime.from()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(regime.to()).isEqualTo(LocalDate.of(2026, 9, 4));
  }

  @Test
  @DisplayName("클러스터 se: 같은 날 5픽 전부 양수여도 D=1 이라 t=0 이고, 여러 날에 분산되면 일별 평균의 se 로 유한한 t 가 나온다")
  void clusterStandardErrorByBaseDate() {
    // 같은 날 5픽 — 픽 단위 se 로는 t≈5.7(0.02 평균, sd 0.0079) 이지만 하루치라 공통 요인을 분리할 수 없다
    List<Map<String, Object>> sameDay = new ArrayList<>();
    double[] excess = {0.01, 0.02, 0.03, 0.02, 0.02};
    for (double e : excess) {
      sameDay.add(pickRow("RISK_ON", "G2510", "{}", e, LocalDate.of(2026, 9, 1)));
    }
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(sameDay);
    LessonService.Cell one = service.cells(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30)).stream()
        .filter(c -> c.signal() == null && c.sector() == null).findFirst().orElseThrow();
    assertThat(one.n()).isEqualTo(5);
    assertThat(one.nDays()).isEqualTo(1);
    assertThat(one.meanExcess()).isCloseTo(0.02, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(one.tStat()).as("D < 2 → t = 0").isZero();

    // 4일 × 2픽: 일별 평균 0.015, 0.025, 0.035, 0.025 → sd 0.00816, se = sd/√4 = 0.00408, t = 0.025/0.00408 ≈ 6.1
    List<Map<String, Object>> spread = new ArrayList<>();
    double[][] byDay = {{0.01, 0.02}, {0.02, 0.03}, {0.03, 0.04}, {0.02, 0.03}};
    for (int d = 0; d < byDay.length; d++) {
      for (double e : byDay[d]) {
        spread.add(pickRow("RISK_ON", "G2510", "{}", e, LocalDate.of(2026, 9, 1 + d)));
      }
    }
    when(jdbc.queryForList(anyString(), anyMap())).thenReturn(spread);
    LessonService.Cell many = service.cells(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30)).stream()
        .filter(c -> c.signal() == null && c.sector() == null).findFirst().orElseThrow();
    assertThat(many.n()).isEqualTo(8);
    assertThat(many.nDays()).isEqualTo(4);
    assertThat(many.meanExcess()).isCloseTo(0.025, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(many.tStat()).isCloseTo(0.025 / (0.008165 / 2), org.assertj.core.data.Offset.offset(0.05));

    Map<String, Object> payload = service.reviewPayload(List.of(many), List.of(), 0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cells = (List<Map<String, Object>>) payload.get("cells");
    assertThat(cells.getFirst()).containsEntry("n", 8).containsEntry("nDays", 4).containsKey("t");
  }

  @Test
  @DisplayName("제안 거부: 형식 불량·근거 부족·종목코드 언급·같은 조건 활성 교훈; 통과분은 슬롯이 남으면 ACTIVE 아니면 CANDIDATE")
  void appliesProposalsWithRules() {
    properties.getLesson().setActiveLimit(1);
    properties.getLesson().setMinCellSamples(20);
    when(lessons.insert(any())).thenReturn(11L, 12L);
    LessonProposalResponse response = new LessonProposalResponse(List.of(
        proposal("SIGNAL", cond("RISK_OFF", "TV_SURGE", ">=", 0.9, null), "하락 국면 급증 픽 초과수익 -1.1%", 32, -2.3, "신뢰도 ≤ 0.6"),   // 통과 → ACTIVE
        proposal("REGIME", cond("RISK_ON", null, null, null, null), "상승 국면 픽 초과수익 +0.8%", 40, 2.5, "신뢰도 유지"),          // 통과 → 슬롯 없음 CANDIDATE
        proposal("SIGNAL", cond(null, "MOM_20D", null, null, null), "op 없음", 30, 3.0, "r"),                                        // 형식 불량
        proposal("SECTOR", cond(null, null, null, null, "G2510"), "n 부족", 10, 3.0, "r"),                                           // 근거 부족
        proposal("SECTOR", cond(null, null, null, null, "G2510"), "005930 이 좋았다", 30, 3.0, "r"),                                  // 티커 언급
        proposal("SIGNAL", cond("RISK_OFF", "TV_SURGE", ">=", 0.9, null), "중복", 30, 3.0, "r")),                                   // 같은 조건
        List.of("유의하지 않음 1"), List.of());

    LessonService.Applied applied = service.apply(response, 7L, "assist", Instant.parse("2026-09-13T00:00:00Z"));

    assertThat(applied.activated()).containsExactly(11L);
    assertThat(applied.candidates()).containsExactly(12L);
    assertThat(applied.rejected()).hasSize(4);
    assertThat(applied.rejected().get(0)).contains("형식 불량");
    assertThat(applied.rejected().get(1)).contains("근거 부족");
    assertThat(applied.rejected().get(2)).contains("종목코드");
    assertThat(applied.rejected().get(3)).contains("같은 조건");
    ArgumentCaptor<LessonRow> saved = ArgumentCaptor.forClass(LessonRow.class);
    verify(lessons, org.mockito.Mockito.times(2)).insert(saved.capture());
    LessonRow first = saved.getAllValues().get(0);
    assertThat(first.status()).isEqualTo(LessonStatus.ACTIVE);
    assertThat(first.scope()).isEqualTo(LessonScope.SIGNAL);
    assertThat(first.condition()).containsEntry("signal", "TV_SURGE").containsEntry("pct", 0.9).doesNotContainKey("sector");
    assertThat(first.lessonText()).startsWith("[관찰] 하락 국면 급증 픽 초과수익 -1.1% (n=32, t=-2.3").contains("[규칙] 신뢰도 ≤ 0.6");
    assertThat(saved.getAllValues().get(1).status()).isEqualTo(LessonStatus.CANDIDATE);
  }

  @Test
  @DisplayName("검토: 활성 4주 경과 후 적용−비적용 ≤ 0 이면 폐기하고, 빈 슬롯은 |t| 큰 후보부터 승격한다")
  void reviewRetiresAndPromotes() {
    LessonRow stale = LessonRow.builder().lessonId(1L).status(LessonStatus.ACTIVE).scope(LessonScope.SIGNAL).condition(Map.of("regime", "RISK_ON"))
        .evidence(Map.of("t", 2.5)).activatedAt(Instant.parse("2026-08-01T00:00:00Z")).build();
    LessonRow fresh = LessonRow.builder().lessonId(2L).status(LessonStatus.ACTIVE).scope(LessonScope.SIGNAL).condition(Map.of("regime", "RISK_OFF"))
        .evidence(Map.of("t", 2.1)).activatedAt(Instant.parse("2026-09-10T00:00:00Z")).build();
    LessonRow weak = LessonRow.builder().lessonId(3L).status(LessonStatus.CANDIDATE).evidence(Map.of("t", 2.2)).build();
    LessonRow strong = LessonRow.builder().lessonId(4L).status(LessonStatus.CANDIDATE).evidence(Map.of("t", -3.1)).build();
    when(lessons.findByStatus(LessonStatus.ACTIVE)).thenReturn(List.of(stale, fresh), List.of(fresh));
    when(lessons.findByStatus(LessonStatus.CANDIDATE)).thenReturn(List.of(weak, strong));
    // stale: 적용 5건 평균 0.001, 비적용 20건 평균 0.004 → 개선 없음. fresh: 아직 기간 미달
    when(jdbc.queryForList(anyString(), anyMap())).thenAnswer(inv -> {
      Map<String, Object> params = inv.getArgument(1);
      if ("[1]".equals(params.get("id"))) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
          rows.add(Map.of("applied", true, "excess_ret", 0.001));
        }
        for (int i = 0; i < 20; i++) {
          rows.add(Map.of("applied", false, "excess_ret", 0.004));
        }
        return rows;
      }
      return List.of();
    });
    properties.getLesson().setActiveLimit(2);

    List<Long> retired = service.review(Instant.parse("2026-09-13T00:00:00Z"));

    assertThat(retired).containsExactly(1L);
    verify(lessons).updateStatus(eq(1L), eq(LessonStatus.RETIRED), any(), org.mockito.ArgumentMatchers.contains("개선 없음"));
    verify(lessons).updatePostPerformance(eq(1L), eq(5), org.mockito.ArgumentMatchers.argThat(d -> d != null && Math.abs(d - 0.001) < 1e-9),
        eq(20), org.mockito.ArgumentMatchers.argThat(d -> d != null && Math.abs(d - 0.004) < 1e-9));
    verify(lessons).updatePostPerformance(2L, 0, null, 0, null);
    verify(lessons, never()).updateStatus(eq(2L), eq(LessonStatus.RETIRED), any(), any());
    verify(lessons).updateStatus(eq(4L), eq(LessonStatus.ACTIVE), any(), any());
    verify(lessons, never()).updateStatus(eq(3L), eq(LessonStatus.ACTIVE), any(), any());
  }

  private static Map<String, Object> pickRow(String regime, String sector, String signalJson, double excess, LocalDate date) {
    Map<String, Object> m = new HashMap<>();
    m.put("regime_code", regime);
    m.put("sector_code", sector);
    m.put("signal_json", signalJson);
    m.put("excess_ret", excess);
    m.put("base_date", Date.valueOf(date));
    return m;
  }

  private static LessonProposalResponse.Condition cond(String regime, String signal, String op, Double pct, String sector) {
    return new LessonProposalResponse.Condition(regime, null, signal, op, pct, sector);
  }

  private static LessonProposalResponse.Proposal proposal(String scope, LessonProposalResponse.Condition c, String observation, int n, double t, String rule) {
    return new LessonProposalResponse.Proposal(scope, c, observation, new LessonProposalResponse.Evidence(n, "2026-06-01", "2026-09-01", -0.011, t), rule);
  }
}
