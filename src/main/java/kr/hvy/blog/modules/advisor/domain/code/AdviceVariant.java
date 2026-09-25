package kr.hvy.blog.modules.advisor.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 판단 변형. Slack 은 LIVE 만 발행하고 나머지는 대조군(섀도)으로 채점만 한다.
 * <p>
 * code 는 상수명과 같다 — DB 컬럼·부분 인덱스·REST 경로 변수(Enum.valueOf)가 모두 상수명 기준이다(stock 모듈 규약).
 */
@Getter
@AllArgsConstructor
public enum AdviceVariant implements EnumCode<String> {
  LIVE("LIVE", "발행본(LLM + 메모리)"),
  QUANT_TOPN("QUANT_TOPN", "정량 top-N 동일가중 섀도(LLM 없음)"),
  LLM_NOMEM("LLM_NOMEM", "메모리 없는 LLM 섀도"),
  /** 뉴스 블록만 뺀 같은 입력(메모리는 LIVE 와 동일) — 뉴스 가치 = LIVE − LLM_NONEWS (advice-v4) */
  LLM_NONEWS("LLM_NONEWS", "뉴스 없는 LLM 섀도"),
  /**
   * 픽 유니버스 필터 없는 정량 top-N 섀도(advice-v7) — QUANT_TOPN 과 같은 규칙·비용 0, 후보만 advisor.markets 전체에서 뽑는다.
   * 같은 기준일 QUANT_TOPN(KOSPI200) − QUANT_TOPN_BROAD 가 유니버스 제한의 비용·편익이다(8주 뒤 날짜 대응 비교로 판정).
   */
  QUANT_TOPN_BROAD("QUANT_TOPN_BROAD", "유니버스 필터 없는 정량 top-N 섀도");

  private final String code;
  private final String desc;
}
