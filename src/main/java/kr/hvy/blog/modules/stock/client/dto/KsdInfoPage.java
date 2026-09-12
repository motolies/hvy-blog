package kr.hvy.blog.modules.stock.client.dto;

import java.util.List;
import java.util.Map;

/**
 * 예탁원정보 한 기간 조회 결과. 행과 함께 잘림 여부를 돌려줘 호출부가 기간을 나눠 다시 받을 수 있게 한다.
 *
 * @param rows      output1 행 (반복 페이지 제외)
 * @param truncated 페이지 상한까지 받았는데 다음 페이지가 남았다 → 이 기간의 행이 잘렸다
 * @param repeated  같은 페이지가 반복돼 중단했다 (연속조회 미지원 신호, run 메타로 드러낸다)
 * @param pageCount 받은 페이지 수
 */
public record KsdInfoPage(List<Map<String, String>> rows, boolean truncated, boolean repeated, int pageCount) {
}
