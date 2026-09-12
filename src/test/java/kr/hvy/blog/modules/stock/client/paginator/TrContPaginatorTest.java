package kr.hvy.blog.modules.stock.client.paginator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.hvy.blog.modules.stock.client.KisApiClient;
import kr.hvy.blog.modules.stock.client.KisCallContext;
import kr.hvy.blog.modules.stock.client.KisResponse;
import kr.hvy.blog.modules.stock.client.dto.KisKsdInfoResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 연속조회 결과 플래그: 상한 도달은 truncated, 동일 페이지 반복은 repeated(즉시 중단), 정상 종료는 둘 다 false.
 * 2026-09-09 이전엔 상한에서 WARN 만 남기고 부분 목록을 돌려줘 예탁원 기업행사 잘림을 아무도 알 수 없었다.
 */
class TrContPaginatorTest {

  private final KisApiClient apiClient = mock(KisApiClient.class);
  private final TrContPaginator paginator = new TrContPaginator(apiClient);
  private final KisCallContext context = KisCallContext.adhoc().withTarget("DIVIDEND");

  private static KisKsdInfoResponse page(String... codes) {
    List<Map<String, String>> rows = new ArrayList<>();
    for (String code : codes) {
      rows.add(Map.of("sht_cd", code));
    }
    return new KisKsdInfoResponse("0", "MCA00000", "정상", rows);
  }

  private static KisResponse<KisKsdInfoResponse> response(KisKsdInfoResponse body, String trCont) {
    return new KisResponse<>(body, trCont, 200);
  }

  @Test
  @DisplayName("마지막 페이지(D) 를 받으면 그대로 끝나고 truncated·repeated 모두 false 다")
  void endsOnLastPage() {
    when(apiClient.get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any()))
        .thenReturn(response(page("005930"), "M"), response(page("000660"), "D"));

    PageResult<KisKsdInfoResponse> result = paginator.paginate("/p", "TR", Map.of("CTS", ""), KisKsdInfoResponse.class,
        context, 10, page -> Map.of());

    assertThat(result.pageCount()).isEqualTo(2);
    assertThat(result.truncated()).isFalse();
    assertThat(result.repeated()).isFalse();
    verify(apiClient, times(2)).get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any());
  }

  @Test
  @DisplayName("상한 페이지까지 받았는데 다음이 남아 있으면 truncated 로 알리고 받은 페이지는 돌려준다")
  void reportsTruncationAtCap() {
    when(apiClient.get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any()))
        .thenReturn(response(page("A"), "M"), response(page("B"), "M"), response(page("C"), "M"));

    PageResult<KisKsdInfoResponse> result = paginator.paginate("/p", "TR", Map.of(), KisKsdInfoResponse.class,
        context, 2, page -> Map.of());

    assertThat(result.pageCount()).isEqualTo(2);
    assertThat(result.truncated()).isTrue();
    assertThat(result.repeated()).isFalse();
    verify(apiClient, times(2)).get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any());
  }

  @Test
  @DisplayName("직전과 같은 페이지가 오면 그 페이지는 버리고 즉시 멈춘다 (상한까지 호출을 낭비하지 않는다)")
  void stopsOnRepeatedPage() {
    when(apiClient.get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any()))
        .thenReturn(response(page("A", "B"), "M"), response(page("A", "B"), "M"), response(page("C"), "M"));

    PageResult<KisKsdInfoResponse> result = paginator.paginate("/p", "TR", Map.of(), KisKsdInfoResponse.class,
        context, 30, page -> Map.of(), (previous, current) -> Objects.equals(previous.output1(), current.output1()));

    assertThat(result.pageCount()).isEqualTo(1);
    assertThat(result.repeated()).isTrue();
    assertThat(result.truncated()).isFalse();
    verify(apiClient, times(2)).get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any());
  }

  @Test
  @DisplayName("두 번째 요청부터 tr_cont=N 과 승계 파라미터가 붙는다")
  void continuationParamsAndTrCont() {
    List<String> trConts = new ArrayList<>();
    List<Map<String, String>> params = new ArrayList<>();
    when(apiClient.get(anyString(), anyString(), anyMap(), any(), eq(KisKsdInfoResponse.class), any()))
        .thenAnswer(invocation -> {
          params.add(Map.copyOf(invocation.getArgument(2)));
          trConts.add(invocation.getArgument(3));
          return params.size() == 1 ? response(page("A"), "F") : response(page("B"), "D");
        });

    paginator.paginate("/p", "TR", Map.of("CTS", ""), KisKsdInfoResponse.class, context, 10,
        page -> Map.of("CTS", page.output1().get(0).get("sht_cd")));

    assertThat(trConts).containsExactly(null, "N");
    assertThat(params.get(0)).containsEntry("CTS", "");
    assertThat(params.get(1)).containsEntry("CTS", "A");
  }
}
