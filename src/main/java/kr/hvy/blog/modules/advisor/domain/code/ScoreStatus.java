package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 채점 결과 상태. 정지·상폐는 마지막 종가로 청산해 학습에 포함한다(빼면 낙관 편향).
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum ScoreStatus implements EnumCode<String> {
  SCORED("SCORED", "정상 채점"),
  MISSING("MISSING", "진입가 없음(재시도)"),
  SUSPENDED("SUSPENDED", "청산일 거래정지(마지막 종가)"),
  DELISTED("DELISTED", "상장폐지(정리매매 종가)");

  private final String code;
  private final String desc;
}
