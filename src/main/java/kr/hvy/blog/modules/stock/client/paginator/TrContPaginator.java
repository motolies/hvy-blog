package kr.hvy.blog.modules.stock.client.paginator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.hvy.blog.modules.stock.client.KisApiClient;
import kr.hvy.blog.modules.stock.client.KisCallContext;
import kr.hvy.blog.modules.stock.client.KisResponse;
import kr.hvy.blog.modules.stock.client.dto.KisEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * tr_cont 연속조회 페이저 (CTCA/CTPF/HHKDB 계열).
 * <p>
 * 최초 요청은 tr_cont 공백, 이후는 N. 응답 헤더 tr_cont 가 F/M 이면 계속, D/E 면 끝.
 * 본문의 ctx_area_fk/nk 같은 승계 키는 응답별로 달라 호출부가 함수로 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrContPaginator {

  private final KisApiClient apiClient;

  /**
   * 모든 페이지를 순서대로 모아 돌려준다.
   *
   * @param continuation 직전 페이지 본문에서 다음 요청에 덧붙일 파라미터를 꺼낸다 (없으면 빈 맵)
   * @param maxPages     페이지 상한 (승계 키 오류로 무한 반복하는 것을 막는다)
   */
  public <T extends KisEnvelope> List<T> paginate(String path, String trId, Map<String, String> baseParams,
      Class<T> type, KisCallContext context, int maxPages, Function<T, Map<String, String>> continuation) {
    List<T> pages = new ArrayList<>();
    Map<String, String> params = new LinkedHashMap<>(baseParams);
    String trCont = null;
    for (int page = 1; page <= maxPages; page++) {
      KisResponse<T> response = apiClient.get(path, trId, params, trCont, type, context);
      pages.add(response.body());
      if (!response.hasNext()) {
        return pages;
      }
      Map<String, String> next = continuation.apply(response.body());
      if (next != null) {
        params.putAll(next);
      }
      trCont = "N";
    }
    log.warn("연속조회 페이지 상한 도달: trId={}, maxPages={}, target={}", trId, maxPages, context.targetKey());
    return pages;
  }
}
