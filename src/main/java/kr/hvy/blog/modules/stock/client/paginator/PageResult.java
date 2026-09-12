package kr.hvy.blog.modules.stock.client.paginator;

import java.util.List;
import kr.hvy.blog.modules.stock.client.dto.KisEnvelope;

/**
 * 연속조회 결과. 페이지네이터는 상한 도달이 의도된 창(휴장일 1년치, 투자자 5페이지)인지 잘림(예탁원 기업행사)인지 알 수 없으므로
 * 판단과 로그는 호출부가 한다. 2026-09-09 이전엔 상한에서 WARN 만 남기고 부분 목록을 돌려줘 잘림을 아무도 알 수 없었다.
 *
 * @param pages     받은 페이지 (반복된 페이지는 제외)
 * @param truncated 상한 페이지까지 받았는데도 다음 페이지가 남아 있었다 (= 행이 잘렸다)
 * @param repeated  직전 페이지와 같은 페이지가 와서 중단했다 (승계 없이 같은 페이지만 오는 API 의 신호)
 */
public record PageResult<T extends KisEnvelope>(List<T> pages, boolean truncated, boolean repeated) {

  public int pageCount() {
    return pages.size();
  }
}
