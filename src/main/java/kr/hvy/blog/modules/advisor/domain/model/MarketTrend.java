package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import java.util.Map;
import kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode;
import lombok.Builder;

/**
 * 지수 1개의 규칙 기반 중기 추세(기준일 확정값). 프롬프트 market.trend 블록과 tb_advisor_advice.trend_json 의 원천.
 *
 * @param indexCode  0001 KOSPI | 1001 KOSDAQ
 * @param tradeDate  라벨을 계산한 지수 지표 행의 날짜 (기준일 이하 최신)
 * @param code       확정 라벨 (confirm-days 연속 확인 뒤)
 * @param rawCode    당일 raw 라벨 (확인 전)
 * @param score      성분 합 [-5, +5]
 * @param components 성분별 점수 {ma20, ma60, ma120, ret60, breadth}
 * @param since      현재 라벨 구간 시작일
 * @param days       현재 라벨 지속 거래일 수
 * @param breadth    MA20 상회 종목 비율 (없으면 null)
 * @param base       과거 같은 라벨 구간의 기저율 통계 (asOf 이하 이력만, 없으면 null)
 */
@Builder(toBuilder = true)
public record MarketTrend(
    String indexCode,
    LocalDate tradeDate,
    MarketTrendCode code,
    MarketTrendCode rawCode,
    int score,
    Map<String, Integer> components,
    LocalDate since,
    int days,
    Double close,
    Double ma20,
    Double ma60,
    Double ma120,
    Double breadth,
    Base base) {

  /**
   * 기저율: 같은 라벨의 과거 에피소드 길이 중앙값과 후행 5·20일 지수 수익률 분포. 겹침 표본이라 n 은 명목값이다.
   */
  public record Base(int episodes, Double medianDays, Forward fwd5, Forward fwd20) {
  }

  public record Forward(int n, Double pUp, Double mean) {
  }
}
