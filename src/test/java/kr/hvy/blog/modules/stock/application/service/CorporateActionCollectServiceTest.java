package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kr.hvy.blog.modules.stock.application.dto.BackfillRequest;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.client.KsdInfoKind;
import kr.hvy.blog.modules.stock.client.dto.KsdInfoPage;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.TriggerType;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import kr.hvy.blog.modules.stock.repository.jdbc.CorporateActionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 예탁원 기업행사 수집: 페이지 상한(잘림)에 걸린 기간은 반으로 나눠 다시 받고, 하루에서도 잘리면 실패로 기록한다.
 * 상폐 보강 후보(기업행사−마스터 차집합)는 REST 가 거부하는 형식(6자 영숫자 밖)을 자동 경로에서도 제외한다.
 */
class CorporateActionCollectServiceTest {

  /** 호출된 (종류, from, to) 를 순서대로 기록 */
  private record Call(KsdInfoKind kind, LocalDate from, LocalDate to) {

    long days() {
      return ChronoUnit.DAYS.between(from, to) + 1;
    }
  }

  private final KisMarketDataPort port = mock(KisMarketDataPort.class);
  private final CorporateActionWriter actionWriter = mock(CorporateActionWriter.class);
  private final CollectRunService runService = mock(CollectRunService.class);
  private final CorporateActionCollectService service = new CorporateActionCollectService(port, actionWriter, new KisProperties());
  private final List<Call> calls = new ArrayList<>();

  private CollectExecution execution() {
    StockCollectRun run = StockCollectRun.builder().runId(11L).jobType(CollectJobType.CORP_ACTION).triggerType(TriggerType.API).build();
    return new CollectExecution(run, BackfillRequest.empty(), runService);
  }

  /** 기간이 maxDays 를 넘으면 잘렸다고(truncated) 답하는 가짜 포트 */
  private void stubTruncatedOver(long maxDays, boolean repeated) {
    when(port.fetchKsdInfo(any(), any(), any(), isNull(), eq(CorporateActionCollectService.MAX_PAGES), any()))
        .thenAnswer(invocation -> {
          Call call = new Call(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
          calls.add(call);
          boolean truncated = call.days() > maxDays;
          return new KsdInfoPage(List.of(), truncated, repeated, truncated ? CorporateActionCollectService.MAX_PAGES : 1);
        });
    when(actionWriter.upsert(anyList())).thenReturn(1);
  }

  @Test
  @DisplayName("잘린 기간은 반으로 나눠 다시 받아 리프 구간이 겹침·빈틈 없이 전체를 덮고, 리프마다 한 번만 저장한다")
  void splitsTruncatedRangeUntilItFits() {
    stubTruncatedOver(10, false);
    CollectExecution execution = execution();

    service.collect(execution, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), null);

    List<Call> dividend = calls.stream().filter(c -> c.kind() == KsdInfoKind.DIVIDEND).toList();
    List<Call> leaves = dividend.stream().filter(c -> c.days() <= 10).sorted((a, b) -> a.from().compareTo(b.from())).toList();
    assertThat(dividend).hasSize(7);                       // 31일 → 16|15 → 8|8, 8|7 : 탐침 3 + 리프 4
    assertThat(leaves).hasSize(4);
    assertThat(leaves.get(0).from()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(leaves.get(3).to()).isEqualTo(LocalDate.of(2024, 1, 31));
    for (int i = 1; i < leaves.size(); i++) {
      assertThat(leaves.get(i).from()).isEqualTo(leaves.get(i - 1).to().plusDays(1)); // 빈틈·겹침 없음
    }
    verify(actionWriter, org.mockito.Mockito.times(4 * KsdInfoKind.values().length)).upsert(anyList());
    assertThat(execution.failureCount()).isZero();
    assertThat(execution.processedCount()).isEqualTo(4 * KsdInfoKind.values().length);
    assertThat(execution.metadataSnapshot()).containsEntry("ksdSplits", 3 * KsdInfoKind.values().length)
        .containsEntry("ksdTruncatedDays", 0).containsEntry("ksdRepeatedPages", 0);
  }

  @Test
  @DisplayName("하루 범위에서도 잘리면 받은 행은 저장하되 그 날짜를 실패로 기록한다")
  void oneDayStillTruncatedIsRecordedAsFailure() {
    stubTruncatedOver(0, false);                           // 모든 기간이 잘린다
    CollectExecution execution = execution();

    service.collect(execution, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2), null);

    int kinds = KsdInfoKind.values().length;
    assertThat(execution.failureCount()).isEqualTo(2 * kinds);
    assertThat(execution.failures().get(0).message()).contains("1일 범위");
    assertThat(execution.metadataSnapshot()).containsEntry("ksdTruncatedDays", 2 * kinds).containsEntry("ksdSplits", kinds);
    verify(actionWriter, org.mockito.Mockito.times(2 * kinds)).upsert(anyList());   // 부분 행도 저장
  }

  @Test
  @DisplayName("같은 페이지 반복(연속조회 미지원 신호)은 실패가 아니라 run 메타 ksdRepeatedPages 로 드러난다")
  void repeatedPagesAreCounted() {
    stubTruncatedOver(1_000, true);
    CollectExecution execution = execution();

    service.collect(execution, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), null);

    assertThat(execution.failureCount()).isZero();
    assertThat(execution.metadataSnapshot()).containsEntry("ksdRepeatedPages", KsdInfoKind.values().length);
  }

  @Test
  @DisplayName("92일 청크로 나누고, 취소가 요청되면 더 부르지 않는다")
  void chunksAndCancel() {
    stubTruncatedOver(1_000, false);
    service.collect(execution(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 9), null);   // 100일
    List<Call> dividend = calls.stream().filter(c -> c.kind() == KsdInfoKind.DIVIDEND).toList();
    assertThat(dividend).hasSize(2);
    assertThat(dividend.get(0).to()).isEqualTo(LocalDate.of(2024, 1, 1).plusDays(CorporateActionCollectService.CHUNK_DAYS - 1L));
    assertThat(dividend.get(1).to()).isEqualTo(LocalDate.of(2024, 4, 9));

    calls.clear();
    when(runService.isCancelRequested(11L)).thenReturn(true);
    service.collect(execution(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), null);
    assertThat(calls).isEmpty();
  }

  @Test
  @DisplayName("Q 접두 ETN·7자·5자리 같은 형식 밖 코드는 걸러지고 6자 코드(우선주 포함)만 남는다")
  void tickersMissingFromMasterFiltersMalformedCodes() {
    when(actionWriter.tickersMissingFromMaster()).thenReturn(Arrays.asList("003410", "Q500001", "A005930", "12345", "003415", null));

    assertThat(service.tickersMissingFromMaster()).containsExactly("003410", "003415");
    verify(port, never()).fetchKsdInfo(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
  }
}
