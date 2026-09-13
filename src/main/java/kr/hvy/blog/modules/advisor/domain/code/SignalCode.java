package kr.hvy.blog.modules.advisor.domain.code;

import java.util.Arrays;
import java.util.List;
import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 정량 시그널 카탈로그. SQL 표현식은 특징 CTE(`f`) 별칭 기준이며 상수 문자열이라 인젝션 여지가 없다.
 * <p>
 * 새 수집 항목을 시그널로 쓰려면 {@code FeatureSql} 의 feat CTE 에 컬럼 1줄 + 여기 상수 1줄 + advisor-seed.sql 시드 1행이면 된다(확장 훅).
 * 수급은 시총이 아니라 60일 평균 거래대금×5 로 정규화한다 — 밸류에이션 스냅샷은 당일만 있어 과거 IC 사전 추정에서 시총을 쓸 수 없고,
 * 라이브와 IC 가 같은 정의를 써야 학습이 성립한다(2026-09-13).
 * VALUE_RANK 는 과거 값이 없어 IC 학습 대상이 아니고(multiplier 1.0 고정), GLOBAL_LINK 는 국면 특징 전용이라 표현식이 없다.
 */
@Getter
@AllArgsConstructor
public enum SignalCode implements EnumCode<String> {
  MOM_20D("MOM_20D", "20일 모멘텀", "f.ret_20d", true, 0.12, true),
  MOM_60D("MOM_60D", "60일 모멘텀", "f.ret_60d", true, 0.10, true),
  TREND_MA("TREND_MA", "MA 정배열 강도(0~4)",
      "((CASE WHEN f.ma_5 > f.ma_20 THEN 1 ELSE 0 END) + (CASE WHEN f.ma_20 > f.ma_60 THEN 1 ELSE 0 END)"
          + " + (CASE WHEN f.ma_60 > f.ma_120 THEN 1 ELSE 0 END) + (CASE WHEN f.adj_close > f.ma_20 THEN 1 ELSE 0 END))::double precision",
      true, 0.12, true),
  NEAR_HIGH_52W("NEAR_HIGH_52W", "52주 고점 근접", "f.dist_high_52w", true, 0.10, true),
  TV_SURGE("TV_SURGE", "거래대금 5/60 급증", "f.tv_ratio_5_60", true, 0.10, true),
  FOREIGN_FLOW("FOREIGN_FLOW", "외국인 5일 순매수 / 60일 평균 거래대금×5", "f.foreign_net_5d / NULLIF(f.tv_avg_60d * 5, 0)", true, 0.12, true),
  INST_FLOW("INST_FLOW", "기관 5일 순매수 / 60일 평균 거래대금×5", "f.institution_net_5d / NULLIF(f.tv_avg_60d * 5, 0)", true, 0.08, true),
  SECTOR_STRENGTH("SECTOR_STRENGTH", "섹터 5일 동일가중 등락 합", "f.sector_cw_5d", true, 0.10, true),
  RS_INDEX("RS_INDEX", "지수 대비 20일 상대강도", "f.ret_20d - f.index_ret_20d", true, 0.08, true),
  VALUE_RANK("VALUE_RANK", "PBR (낮을수록)", "f.pbr", false, 0.05, false),
  VOL_20D("VOL_20D", "20일 변동성 (낮을수록)", "f.vol_20d", false, 0.03, true),
  GLOBAL_LINK("GLOBAL_LINK", "해외 연동 (국면 특징 전용)", null, true, 0.00, false);

  private final String code;
  private final String desc;

  /** feat CTE 별칭(f) 기준 SQL 표현식. null 이면 종목 점수·IC 에서 제외 */
  private final String expression;

  /** 값이 클수록 좋은가. false 면 백분위를 뒤집는다 */
  private final boolean higherIsBetter;

  /** 설계 사전 가중치 (시드와 같아야 한다) */
  private final double baseWeight;

  /** IC 로 배수를 학습하는 대상인지 (밸류·국면 전용은 false) */
  private final boolean icLearnable;

  /** 종목 점수에 쓸 수 있는 시그널 (표현식 있음) */
  public static List<SignalCode> scorable() {
    return Arrays.stream(values()).filter(s -> s.expression != null).toList();
  }

  /** IC 를 계산하는 시그널 (표현식 있고 학습 대상) */
  public static List<SignalCode> learnable() {
    return Arrays.stream(values()).filter(s -> s.expression != null && s.icLearnable).toList();
  }

  /** 백분위를 점수[-1,1] 로 바꾸는 부호 */
  public double sign() {
    return higherIsBetter ? 1.0 : -1.0;
  }
}
