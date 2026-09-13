package kr.hvy.blog.modules.advisor.domain.code;

import java.util.Arrays;
import java.util.Optional;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 채팅 봇 metricTopN 도구가 정렬할 수 있는 tb_stock_daily_metric 컬럼. ORDER BY 대상은 바인딩할 수 없으므로 <b>이 enum 의 {@link #column()} 만</b>
 * SQL 에 보간한다 — 모델이 준 문자열은 {@link #parse(String)} 를 통과해야만 SQL 에 닿는다(FeatureSql 이 SignalCode 표현식을 다루는 방식과 동일).
 * code 는 상수명과 같다(advisor enum 규약).
 */
@Getter
@AllArgsConstructor
public enum MetricColumn implements EnumCode<String> {
  RET_1D("RET_1D", "1일 수익률", "ret_1d"),
  RET_5D("RET_5D", "5일 수익률", "ret_5d"),
  RET_20D("RET_20D", "20일 수익률(모멘텀)", "ret_20d"),
  RET_60D("RET_60D", "60일 수익률", "ret_60d"),
  RET_120D("RET_120D", "120일 수익률", "ret_120d"),
  DIST_MA20("DIST_MA20", "20일 이동평균 이격", "dist_ma20"),
  DIST_MA60("DIST_MA60", "60일 이동평균 이격", "dist_ma60"),
  DIST_HIGH_52W("DIST_HIGH_52W", "52주 고가 대비 이격", "dist_high_52w"),
  TV_RATIO_5_60("TV_RATIO_5_60", "거래대금 5일/60일 비율(거래 급증)", "tv_ratio_5_60"),
  FOREIGN_NET_5D("FOREIGN_NET_5D", "외국인 5일 순매수", "foreign_net_5d"),
  INSTITUTION_NET_5D("INSTITUTION_NET_5D", "기관 5일 순매수", "institution_net_5d");

  private final String code;
  private final String desc;
  /** tb_stock_daily_metric 의 실제 컬럼명 — SQL 에 보간되는 유일한 값 */
  private final String column;

  /**
   * 모델이 준 문자열(상수명 또는 컬럼명, 대소문자 무관)을 enum 으로. 모르면 빈 Optional — SQL 에 도달하지 않는다.
   */
  public static Optional<MetricColumn> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String v = value.trim().toUpperCase();
    return Arrays.stream(values()).filter(c -> c.code.equals(v) || c.column.equalsIgnoreCase(v)).findFirst();
  }

  /**
   * 도구 설명문에 넣는 허용 목록.
   */
  public static String allowedList() {
    return String.join(", ", Arrays.stream(values()).map(MetricColumn::getColumn).toList());
  }
}
