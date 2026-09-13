package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.application.AdvisorProperties;
import kr.hvy.blog.modules.advisor.domain.code.AdviceVariant;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.MorningVerdict;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.GlobalLink;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.MorningCheckWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * 아침 점검 규칙: 예상 갭 = β × 밤사이 미국 수익률, 임계 = σ_1d × 배수, 어제 방향과 같은 부호면 강화·반대면 주의·임계 미만이면 유지. 원 판단은 건드리지 않는다.
 */
class MorningCheckJobTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final GlobalLinkService links = mock(GlobalLinkService.class);
  private final MorningCheckWriter checkWriter = mock(MorningCheckWriter.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);
  private final MorningCheckJob job = new MorningCheckJob(properties, calendar, adviceWriter, links, checkWriter, notifier);

  private final LocalDate base = LocalDate.of(2026, 9, 11); // 금요일 판단
  private final LocalDate today = LocalDate.of(2026, 9, 14); // 월요일 07:30
  private final AdviceHeader advice = AdviceHeader.builder().adviceId(842L).runId(1L).baseDate(base).adviceKind("DAILY").variant(AdviceVariant.LIVE)
      .horizonDays(5).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL).pUp(0.7).build();

  @BeforeEach
  void setUp() {
    when(calendar.isTradingDay(any())).thenReturn(true);
    when(calendar.lastTradingDayOnOrBefore(today.minusDays(1))).thenReturn(base);
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(advice));
    when(checkWriter.findByAdvice(842L)).thenReturn(Optional.empty());
    when(checkWriter.insert(any())).thenReturn(7L);
    when(notifier.publish(any())).thenReturn(true);
    when(links.overnight(anyList(), eq(today))).thenReturn(Map.of(
        "SPX", new GlobalLinkService.Overnight("SPX", base, 0.02),
        "SOX", new GlobalLinkService.Overnight("SOX", base, 0.03),
        "COMP", new GlobalLinkService.Overnight("COMP", base, 0.01),
        "FX@KRW", new GlobalLinkService.Overnight("FX@KRW", today.minusDays(1), 0.001)));
    when(links.link("0001", "SPX", base)).thenReturn(new GlobalLink("0001", "SPX", 0.6, 0.55, 60));
    when(links.link("1001", "COMP", base)).thenReturn(new GlobalLink("1001", "COMP", 0.8, 0.5, 60));
    when(links.sigma1d(any(), eq(base))).thenReturn(0.01);
  }

  @Test
  @DisplayName("KOSPI 예상 갭 +1.2% ≥ 임계 1% 이고 어제 ▲ → 강화, KOSDAQ 예상 갭 +0.8% < 임계 → 유지, 전체 판정은 강화")
  void reinforceAndHold() {
    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    ArgumentCaptor<MorningCheckRow> row = ArgumentCaptor.forClass(MorningCheckRow.class);
    verify(checkWriter).insert(row.capture());
    MorningCheckRow saved = row.getValue();
    assertThat(saved.adviceId()).isEqualTo(842L);
    assertThat(saved.baseDate()).isEqualTo(base);
    assertThat(saved.usDate()).isEqualTo(base);
    assertThat(saved.gapKospi()).isCloseTo(0.012, within(1e-9));
    assertThat(saved.gapKosdaq()).isCloseTo(0.008, within(1e-9));
    assertThat(saved.verdict()).isEqualTo(MorningVerdict.REINFORCE);
    @SuppressWarnings("unchecked")
    Map<String, Object> index = (Map<String, Object>) saved.detailJson().get("index");
    @SuppressWarnings("unchecked")
    Map<String, Object> kospi = (Map<String, Object>) index.get("0001");
    assertThat(kospi).containsEntry("symbol", "SPX").containsEntry("beta", 0.6).containsEntry("threshold", 0.01).containsEntry("verdict", "REINFORCE")
        .containsEntry("predicted", "UP");
    @SuppressWarnings("unchecked")
    Map<String, Object> kosdaq = (Map<String, Object>) index.get("1001");
    assertThat(kosdaq).containsEntry("verdict", "HOLD");
    assertThat(saved.detailJson()).containsEntry("usClosed", false);
    verify(checkWriter).markPublished(eq(7L), any());
    verify(notifier).publish(any());
    verify(adviceWriter, never()).insertHeader(any());
    assertThat(execution.metadata("verdict")).isEqualTo("REINFORCE");
    assertThat(execution.metadata("checkId")).isEqualTo(7L);
  }

  @Test
  @DisplayName("어제 ▼ 예측에 +갭이면 주의, NEUTRAL 예측에 큰 갭도 주의 — 전체 판정은 가장 심각한 것")
  void cautionWins() {
    AdviceHeader down = advice.toBuilder().kospiDir(DirectionCall.DOWN).kosdaqDir(DirectionCall.NEUTRAL).build();
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(down));
    when(links.link("1001", "COMP", base)).thenReturn(new GlobalLink("1001", "COMP", 1.5, 0.5, 60)); // 갭 1.5% ≥ 1%

    job.execute(execution());

    ArgumentCaptor<MorningCheckRow> row = ArgumentCaptor.forClass(MorningCheckRow.class);
    verify(checkWriter).insert(row.capture());
    assertThat(row.getValue().verdict()).isEqualTo(MorningVerdict.CAUTION);
    assertThat(MorningCheckJob.verdict(0.012, 0.01, DirectionCall.DOWN)).isEqualTo(MorningVerdict.CAUTION);
    assertThat(MorningCheckJob.verdict(0.015, 0.01, DirectionCall.NEUTRAL)).isEqualTo(MorningVerdict.CAUTION);
    assertThat(MorningCheckJob.verdict(-0.012, 0.01, DirectionCall.DOWN)).isEqualTo(MorningVerdict.REINFORCE);
    assertThat(MorningCheckJob.verdict(0.005, 0.01, DirectionCall.DOWN)).isEqualTo(MorningVerdict.HOLD);
    assertThat(MorningCheckJob.verdict(null, 0.01, DirectionCall.UP)).isEqualTo(MorningVerdict.HOLD);
  }

  @Test
  @DisplayName("기준일에 미국이 휴장이었으면(usDate < base) 새 정보가 없다 — 예상 갭 없이 유지로 기록, 너무 오래됐으면 SKIPPED")
  void usClosedOrStale() {
    LocalDate thursday = base.minusDays(1);
    when(links.overnight(anyList(), eq(today))).thenReturn(Map.of(
        "SPX", new GlobalLinkService.Overnight("SPX", thursday, 0.02), "COMP", new GlobalLinkService.Overnight("COMP", thursday, 0.01)));
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.isSkipped()).isFalse();
    ArgumentCaptor<MorningCheckRow> row = ArgumentCaptor.forClass(MorningCheckRow.class);
    verify(checkWriter).insert(row.capture());
    assertThat(row.getValue().verdict()).isEqualTo(MorningVerdict.HOLD);
    assertThat(row.getValue().gapKospi()).isNull();
    assertThat(row.getValue().usDate()).isEqualTo(thursday);
    assertThat(row.getValue().detailJson()).containsEntry("usClosed", true);

    when(links.overnight(anyList(), eq(today))).thenReturn(Map.of("SPX", new GlobalLinkService.Overnight("SPX", base.minusDays(10), 0.02)));
    AdvisorExecution stale = execution();
    job.execute(stale);
    assertThat(stale.isSkipped()).isTrue();
  }

  @Test
  @DisplayName("휴장일·판단 없음·이미 점검이면 SKIPPED 이고 아무것도 저장하지 않는다")
  void skips() {
    when(calendar.isTradingDay(today)).thenReturn(false);
    AdvisorExecution holiday = execution();
    job.execute(holiday);
    assertThat(holiday.isSkipped()).isTrue();

    when(calendar.isTradingDay(today)).thenReturn(true);
    when(checkWriter.findByAdvice(842L)).thenReturn(Optional.of(MorningCheckRow.builder().adviceId(842L).verdict(MorningVerdict.HOLD).build()));
    AdvisorExecution already = execution();
    job.execute(already);
    assertThat(already.isSkipped()).isTrue();

    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.empty());
    when(checkWriter.findByAdvice(anyLong())).thenReturn(Optional.empty());
    AdvisorExecution none = execution();
    job.execute(none);
    assertThat(none.isSkipped()).isTrue();
    verify(checkWriter, never()).insert(any());
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(31L).jobType(AdvisorJobType.MORNING_CHECK).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(today).build();
    return new AdvisorExecution(run, today, properties);
  }
}
