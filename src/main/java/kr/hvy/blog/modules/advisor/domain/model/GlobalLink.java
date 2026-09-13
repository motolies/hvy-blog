package kr.hvy.blog.modules.advisor.domain.model;

/**
 * 국내 지수 ↔ 미국 심볼의 연동 강도 (직전 N 거래일). beta 는 미국 직전 세션 1일 수익률에 대한 국내 다음 거래일 1일 수익률의 회귀 기울기, corr 는 상관계수, n 은 표본 쌍 수.
 * 판단 시점(19:30)엔 미국 당일 장이 열리지 않았으므로 방향 정보가 아니라 "국내가 미국에 얼마나 끌려다니는가" 이고, 예측 가치는 07:30 아침 점검에서 β × 밤사이 수익률로 쓴다.
 *
 * @param krIndex  0001 | 1001
 * @param usSymbol SPX·COMP·SOX 등 (tb_stock_global_market_daily.symbol)
 */
public record GlobalLink(String krIndex, String usSymbol, Double beta, Double corr, int n) {
}
