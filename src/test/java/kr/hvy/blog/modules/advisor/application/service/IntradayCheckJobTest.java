package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorTriggerType;
import kr.hvy.blog.modules.advisor.domain.code.DirectionCall;
import kr.hvy.blog.modules.advisor.domain.code.IntradayVerdict;
import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;
import kr.hvy.blog.modules.advisor.repository.jdbc.AdviceWriter;
import kr.hvy.blog.modules.advisor.repository.jdbc.IntradayCheckWriter;
import kr.hvy.blog.modules.stock.application.service.MarketCalendarService;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisIndexPriceResponse;
import kr.hvy.blog.modules.stock.client.dto.KisPriceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

/**
 * 장중 점검 규칙: 직전 영업일 판단이 없으면 SKIPPED, 지수·픽 대조와 일치율·판정, KIS 실패 1건은 삼키고 나머지로 판정, 저장·발행.
 */
class IntradayCheckJobTest {

  private final AdvisorProperties properties = new AdvisorProperties(new MockEnvironment());
  private final KisProperties kisProperties = new KisProperties();
  private final KisMarketDataPort marketData = mock(KisMarketDataPort.class);
  private final MarketCalendarService calendar = mock(MarketCalendarService.class);
  private final AdviceWriter adviceWriter = mock(AdviceWriter.class);
  private final IntradayCheckWriter checkWriter = mock(IntradayCheckWriter.class);
  private final AdvisorNotifier notifier = mock(AdvisorNotifier.class);
  private final LocalDate today = LocalDate.of(2026, 9, 12);
  private IntradayCheckJob job;

  @BeforeEach
  void setUp() {
    kisProperties.setAppKey("k");
    kisProperties.setAppSecret("s");
    job = new IntradayCheckJob(properties, kisProperties, marketData, calendar, adviceWriter, checkWriter, notifier);
    when(calendar.isTradingDay(today)).thenReturn(true);
    when(calendar.lastTradingDayOnOrBefore(today.minusDays(1))).thenReturn(today.minusDays(1));
    when(notifier.publish(any())).thenReturn(true);
    when(checkWriter.insert(any())).thenReturn(5L);
  }

  @Test
  @DisplayName("직전 영업일 LIVE 판단이 없으면 SKIPPED, KIS 호출 없음")
  void skipsWithoutAdvice() {
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.empty());
    AdvisorExecution execution = execution();
    job.execute(execution);
    assertThat(execution.isSkipped()).isTrue();
    verify(marketData, never()).fetchIndexPrice(any(), any());
  }

  @Test
  @DisplayName("지수·픽을 대조해 일치율과 판정을 저장·발행하고, 종목 1건 실패는 삼킨다")
  void checksAndPublishes() {
    AdviceHeader advice = AdviceHeader.builder().adviceId(842L).baseDate(today.minusDays(1)).kospiDir(DirectionCall.UP).kosdaqDir(DirectionCall.NEUTRAL).build();
    when(adviceWriter.findLatest(AdviceVariant.LIVE, today.minusDays(1))).thenReturn(Optional.of(advice));
    when(adviceWriter.picks(842L)).thenReturn(List.of(
        PickRow.builder().ticker("005930").pickRank(1).direction(PickDirection.LONG).conviction(0.8).build(),
        PickRow.builder().ticker("000660").pickRank(2).direction(PickDirection.LONG).conviction(0.7).build(),
        PickRow.builder().ticker("035420").pickRank(3).direction(PickDirection.AVOID).conviction(0.6).build(),
        PickRow.builder().ticker("999999").pickRank(4).direction(PickDirection.LONG).conviction(0.6).build()));
    when(adviceWriter.candidates(842L)).thenReturn(List.of(
        CandidateRow.builder().ticker("000660").stockName("SK하이닉스").build()));
    when(marketData.fetchIndexPrice(eq("0001"), any())).thenReturn(index("2740.10", "0.31"));
    when(marketData.fetchIndexPrice(eq("1001"), any())).thenReturn(index("880.00", "-0.12"));
    when(marketData.fetchPrice(eq("005930"), any())).thenReturn(price("71000", "1.20"));
    when(marketData.fetchPrice(eq("000660"), any())).thenReturn(price("180000", "-2.10"));
    when(marketData.fetchPrice(eq("035420"), any())).thenReturn(price("200000", "-0.50"));
    when(marketData.fetchPrice(eq("999999"), any())).thenThrow(new IllegalStateException("EGW00123"));

    AdvisorExecution execution = execution();
    job.execute(execution);

    assertThat(execution.isSkipped()).isFalse();
    assertThat(execution.failureCount()).isEqualTo(1);
    assertThat(execution.decideStatus()).isEqualTo(AdvisorStatus.PARTIAL);
    ArgumentCaptor<IntradayCheckRow> saved = ArgumentCaptor.forClass(IntradayCheckRow.class);
    verify(checkWriter).insert(saved.capture());
    IntradayCheckRow row = saved.getValue();
    assertThat(row.adviceId()).isEqualTo(842L);
    assertThat(row.agreementRatio()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9)); // 005930 ✓, 000660 ✗, 035420(AVOID, 음수) ✓
    assertThat(row.verdict()).isEqualTo(IntradayVerdict.ON_TRACK);
    assertThat(row.comment()).contains("판단 유지").contains("SK하이닉스 -2.1%");
    @SuppressWarnings("unchecked")
    Map<String, Object> kospi = (Map<String, Object>) row.indexJson().get("0001");
    assertThat(kospi).containsEntry("agree", true).containsEntry("changeRate", 0.31);
    @SuppressWarnings("unchecked")
    Map<String, Object> kosdaq = (Map<String, Object>) row.indexJson().get("1001");
    assertThat(kosdaq).containsEntry("agree", true); // NEUTRAL 예측, |−0.12| < 0.3
    assertThat(row.pickJson()).hasSize(4);
    assertThat(row.pickJson().get(3)).containsKey("error");
    verify(notifier).publish(any());
    assertThat(execution.metadata("verdict")).isEqualTo("ON_TRACK");
  }

  @Test
  @DisplayName("일치율 30% 미만이면 OFF_TRACK")
  void offTrack() {
    AdviceHeader advice = AdviceHeader.builder().adviceId(1L).baseDate(today.minusDays(1)).kospiDir(DirectionCall.DOWN).kosdaqDir(DirectionCall.DOWN).build();
    when(adviceWriter.findLatest(AdviceVariate(), today.minusDays(1))).thenReturn(Optional.of(advice));
    when(adviceWriter.picks(1L)).thenReturn(List.of(
        PickRow.builder().ticker("A").pickRank(1).direction(PickDirection.LONG).conviction(0.7).build(),
        PickRow.builder().ticker("B").pickRank(2).direction(PickDirection.LONG).conviction(0.7).build()));
    when(adviceWriter.candidates(1L)).thenReturn(List.of());
    when(marketData.fetchIndexPrice(any(), any())).thenReturn(index("1", "1.0"));
    when(marketData.fetchPrice(any(), any())).thenReturn(price("1", "-1.0"));
    AdvisorExecution execution = execution();
    job.execute(execution);
    ArgumentCaptor<IntradayCheckRow> saved = ArgumentCaptor.forClass(IntradayCheckRow.class);
    verify(checkWriter).insert(saved.capture());
    assertThat(saved.getValue().verdict()).isEqualTo(IntradayVerdict.OFF_TRACK);
    assertThat(saved.getValue().agreementRatio()).isEqualTo(0.0);
  }

  private static AdviceVariant AdviceVariate() {
    return AdviceVariant.LIVE;
  }

  private static KisIndexPriceResponse.Output index(String value, String rate) {
    return new KisIndexPriceResponse.Output(value, null, null, rate, null, null);
  }

  private static KisPriceResponse.Output price(String value, String rate) {
    return new KisPriceResponse.Output(null, value, null, null, rate, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private AdvisorExecution execution() {
    AdvisorRun run = AdvisorRun.builder().runId(9L).jobType(AdvisorJobType.INTRADAY).triggerType(AdvisorTriggerType.SCHEDULER).baseDate(today).build();
    return new AdvisorExecution(run, today, properties);
  }
}
