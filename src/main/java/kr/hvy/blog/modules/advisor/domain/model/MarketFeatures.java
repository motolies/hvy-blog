package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 시장 국면 특징 (프롬프트 market·sectors 블록의 원천). 전부 기준일 이하 데이터만.
 *
 * @param sigma5d   지수별 σ_5d (직전 N일 ret_1d 표준편차 × √5) — 국면 채점 밴드
 * @param globalAsOf 해외 마지막 거래일 (국내 기준일 새벽까지의 미국 종가 = T-1)
 */
public record MarketFeatures(
    LocalDate asOf,
    List<IndexFeature> indices,
    List<FlowFeature> flows,
    List<GlobalFeature> global,
    List<SectorFeature> topSectors,
    List<SectorFeature> bottomSectors,
    Map<String, Double> sigma5d,
    LocalDate globalAsOf) {

  public record IndexFeature(String code, String name, double close, Double r1, Double r5, Double r20, Double r60, Double distMa20, Double distMa60) {
  }

  /** 시장별 투자자 순매수 금액(원): 1일·5일 합 */
  public record FlowFeature(String market, Long frgn1, Long inst1, Long indi1, Long frgn5, Long inst5, Long indi5) {
  }

  public record GlobalFeature(String symbol, LocalDate date, double close, Double r1, Double r5) {
  }

  /** 섹터 5일 시총가중 등락 합, 최신일 상승 비율·52주 고점 근접 비율, 외인 5일 순매수 합, 구성 종목 수 */
  public record SectorFeature(String code, String name, Double cw5d, Double rising, Double nearHigh, Long frgn5, int members) {
  }
}
