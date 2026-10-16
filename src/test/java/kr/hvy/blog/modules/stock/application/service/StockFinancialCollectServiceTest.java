package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.stock.client.FinancialKind;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.domain.model.FinancialRow;
import kr.hvy.blog.modules.stock.repository.jdbc.StockFinancialWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 재무 수집 1종목: 마스터 결산월이 연간 행 필터로 전달된다(KIS 연간 응답 끝의 진행 중 회계연도 중간기간 제외, 2026-10-16).
 */
class StockFinancialCollectServiceTest {

  private final KisMarketDataPort port = mock(KisMarketDataPort.class);
  private final StockFinancialWriter writer = mock(StockFinancialWriter.class);
  private final StockFinancialCollectService service = new StockFinancialCollectService(port, writer,
      mock(TargetResolver.class), mock(ConcurrentTargetRunner.class), mock(CollectCheckpointService.class));

  @Test
  @DisplayName("연간은 결산월(12) 행만 저장하고 분기는 202606 을 그대로 저장한다")
  @SuppressWarnings("unchecked")
  void collectOnePassesSettleMonth() {
    List<Map<String, String>> periods = List.of(
        Map.of("stac_yymm", "202512", "sale_account", "3336059"),
        Map.of("stac_yymm", "202606", "sale_account", "3053729"));
    when(port.fetchFinancial(any(FinancialKind.class), anyString(), anyBoolean(), any())).thenReturn(List.of());
    when(port.fetchFinancial(eq(FinancialKind.values()[0]), eq("005930"), anyBoolean(), any())).thenReturn(periods);

    service.collectOne(mock(CollectExecution.class), "005930", "12");

    ArgumentCaptor<List<FinancialRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(writer, times(2)).apply(eq("005930"), rows.capture());
    assertThat(rows.getAllValues().get(0)).extracting(FinancialRow::fiscalPeriod, FinancialRow::periodType)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("202512", FinancialRow.PERIOD_ANNUAL));
    assertThat(rows.getAllValues().get(1)).extracting(FinancialRow::fiscalPeriod)
        .containsExactly("202512", "202606");
  }
}
