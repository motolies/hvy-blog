package kr.hvy.blog.modules.stock.client.paginator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
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
 * 상한 도달·동일 페이지 반복은 {@link PageResult} 플래그로 돌려주고 여기서는 DEBUG 만 남긴다 — 의도된 상한인지 잘림인지는 호출부만 안다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrContPaginator {

  private final KisApiClient apiClient;

  /**
   * 모든 페이지를 순서대로 모아 돌려준다 (동일 페이지 감지 없음).
   *
   * @param continuation 직전 페이지 본문에서 다음 요청에 덧붙일 파라미터를 꺼낸다 (없으면 빈 맵)
   * @param maxPages     페이지 상한 (승계 키 오류로 무한 반복하는 것을 막는다)
   */
  public <T extends KisEnvelope> PageResult<T> paginate(String path, String trId, Map<String, String> baseParams,
      Class<T> type, KisCallContext context, int maxPages, Function<T, Map<String, String>> continuation) {
    return paginate(path, trId, baseParams, type, context, maxPages, continuation, (previous, current) -> false);
  }

  /**
   * 모든 페이지를 순서대로 모아 돌려준다. 직전 페이지와 같은 페이지가 오면(승계 키 없이 첫 페이지만 반복하는 API) 즉시 멈추고
   * {@code repeated} 를 켠다 — 2026-09-09 이전엔 상한까지 다 받은 뒤 사후에 걸러 호출만 낭비했다.
   *
   * @param samePage 직전 페이지와 같은지 판정 (본문 비교는 응답 유형마다 달라 호출부가 넘긴다)
   */
  public <T extends KisEnvelope> PageResult<T> paginate(String path, String trId, Map<String, String> baseParams,
      Class<T> type, KisCallContext context, int maxPages, Function<T, Map<String, String>> continuation,
      BiPredicate<T, T> samePage) {
    List<T> pages = new ArrayList<>();
    Map<String, String> params = new LinkedHashMap<>(baseParams);
    String trCont = null;
    T previous = null;
    for (int page = 1; page <= maxPages; page++) {
      KisResponse<T> response = apiClient.get(path, trId, params, trCont, type, context);
      T body = response.body();
      if (previous != null && samePage.test(previous, body)) {
        log.debug("연속조회 동일 페이지 반복 → 중단: trId={}, page={}, target={}", trId, page, context.targetKey());
        return new PageResult<>(pages, false, true);
      }
      pages.add(body);
      if (!response.hasNext()) {
        return new PageResult<>(pages, false, false);
      }
      Map<String, String> next = continuation.apply(body);
      if (next != null) {
        params.putAll(next);
      }
      trCont = "N";
      previous = body;
    }
    log.debug("연속조회 페이지 상한 도달: trId={}, maxPages={}, target={}", trId, maxPages, context.targetKey());
    return new PageResult<>(pages, true, false);
  }
}
