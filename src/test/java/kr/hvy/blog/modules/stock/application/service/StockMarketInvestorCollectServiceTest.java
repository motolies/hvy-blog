package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.dto.KisMarketInvestorResponse;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.repository.jdbc.MarketIndexWriter;
import kr.hvy.blog.modules.stock.repository.jdbc.StockMarketInvestorWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시장별 투자자 백필 루프: 영업일 역순 호출, 다일 응답 시 커서 점프, 빈 응답 3연속 EXHAUSTED, 목표 시작일 도달 DONE.
 */
class StockMarketInvestorCollectServiceTest {

  private static final LocalDate D1 = LocalDate.of(2026, 9, 1); // 화
  private static final LocalDate D2 = LocalDate.of(2026, 9, 2);
  private static final LocalDate D3 = LocalDate.of(2026, 9, 3);
  private static final LocalDate D4 = LocalDate.of(2026, 9, 4);
  private static final LocalDate D5 = LocalDate.of(2026, 9, 7); // 월 (주말 건너뜀)

  private final KisMarketDataPort port = mock(KisMarketDataPort.class);
  private final StockMarketInvestorWriter writer = mock(StockMarketInvestorWriter.class);
  private final MarketIndexWriter indexWriter = mock(MarketIndexWriter.class);
  private final CollectCheckpointService checkpointService = mock(CollectCheckpointService.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  private final StockMarketInvestorCollectService service = new StockMarketInvestorCollectService(port, writer, indexWriter,
      checkpointService, new KisProperties());
  private final List<LocalDate> calledDays = new ArrayList<>();
  private StockCollectCheckpoint kospi;

  @BeforeEach
  void setUp() {
    when(indexWriter.tradeDates(eq("0001"), any(), any())).thenReturn(List.of(D5, D4, D3, D2, D1));
    kospi = StockCollectCheckpoint.pending(CollectJobType.MARKET_INVESTOR_BACKFILL, "KOSPI", D5);
    when(checkpointService.findResumable(eq(CollectJobType.MARKET_INVESTOR_BACKFILL), anyInt())).thenReturn(List.of(kospi));
    when(checkpointService.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(writer.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
  }

  @Test
  @DisplayName("하루치 응답이면 영업일을 하나씩 거꾸로 호출하고 목표 시작일에 닿으면 DONE 이다")
  void walksTradingDaysBackward() {
    when(port.fetchMarketInvestorDaily(eq(MarketType.KOSPI), any(), any())).thenAnswer(inv -> {
      LocalDate day = inv.getArgument(1);
      calledDays.add(day);
      return List.of(row(day));
    });
    CollectExecution exec = execution(D1, D5);

    service.execute(exec);

    assertThat(calledDays).containsExactly(D5, D4, D3, D2, D1);
    assertThat(kospi.getStatus()).isEqualTo(CheckpointStatus.DONE);
    assertThat(kospi.getEarliestLoaded()).isEqualTo(D1);
    assertThat(kospi.getLatestLoaded()).isEqualTo(D5);
    assertThat(exec.totalRows()).isEqualTo(5);
    assertThat(exec.metadataSnapshot()).containsEntry("tradingDays", 5);
  }

  @Test
  @DisplayName("여러 날이 한 번에 오면 최소 일자 직전 영업일로 커서를 옮겨 중복 호출하지 않는다")
  void jumpsCursorOnMultiDayResponse() {
    when(port.fetchMarketInvestorDaily(eq(MarketType.KOSPI), any(), any())).thenAnswer(inv -> {
      LocalDate day = inv.getArgument(1);
      calledDays.add(day);
      return day.equals(D5) ? List.of(row(D5), row(D4), row(D3)) : List.of(row(day));
    });

    service.execute(execution(D1, D5));

    assertThat(calledDays).containsExactly(D5, D2, D1);
    assertThat(kospi.getStatus()).isEqualTo(CheckpointStatus.DONE);
  }

  @Test
  @DisplayName("빈 응답이 3번 이어지면 KIS 소급 한계로 보고 EXHAUSTED 로 남긴다")
  void exhaustedAfterEmptyStreak() {
    when(port.fetchMarketInvestorDaily(eq(MarketType.KOSPI), any(), any())).thenAnswer(inv -> {
      LocalDate day = inv.getArgument(1);
      calledDays.add(day);
      return day.equals(D5) ? List.of(row(D5)) : List.of();
    });

    service.execute(execution(D1, D5));

    assertThat(calledDays).containsExactly(D5, D4, D3, D2);
    assertThat(kospi.getStatus()).isEqualTo(CheckpointStatus.EXHAUSTED);
    assertThat(kospi.getEarliestLoaded()).isEqualTo(D5);
  }

  @Test
  @DisplayName("영업일 집합이 비어 있으면 INDEX_BACKFILL 선행을 요구한다")
  void requiresTradingCalendar() {
    when(indexWriter.tradeDates(eq("0001"), any(), any())).thenReturn(List.of());

    assertThatThrownBy(() -> service.execute(execution(D1, D5)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("INDEX_BACKFILL");
  }

  private CollectExecution execution(LocalDate start, LocalDate end) {
    StockCollectRun run = StockCollectRun.builder().runId(5L).jobType(CollectJobType.MARKET_INVESTOR_BACKFILL)
        .triggerType(TriggerType.API).build();
    return new CollectExecution(run, new BackfillRequest(start, end, null, null, null, null, null, null), runService);
  }

  private static KisMarketInvestorResponse.Row row(LocalDate day) {
    String d = day.toString().replace("-", "");
    return new KisMarketInvestorResponse.Row(d, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
        "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
  }
}
