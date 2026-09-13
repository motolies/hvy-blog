package kr.hvy.blog.modules.advisor.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 시장 국면 특징 (프롬프트 market·sectors·dataAsOf·window 블록의 원천). 전부 기준일 이하 데이터만.
 *
 * @param sigma5d              지수별 σ_5d (직전 N일 ret_1d 표준편차 × √5) — 국면 채점 밴드
 * @param globalAsOf           미국 지수 심볼(FX 제외)의 마지막 거래일 중 가장 이른 날 — 심볼별 max 를 쓰면 미국 휴장일에 환율만 갱신돼 오래된 지수를 감춘다
 * @param globalAgeTradingDays 기준일과 globalAsOf 사이의 국내 영업일 수 (정상 1, 미국 휴장·수집 실패면 2 이상)
 * @param flowAsOf             시장 수급 마지막 날 (당일 수급은 잠정치)
 * @param sectorAsOf           섹터 MV 마지막 날
 * @param entryDate            적용 진입일(예정) = 기준일 다음 영업일 시가
 * @param exitDate             적용 청산일(예정) = h번째 영업일 종가
 * @param trends               지수별 규칙 기반 중기 추세 (KOSPI·KOSDAQ)
 * @param links                국내 지수 ↔ 미국 심볼 연동 강도 (advice-v3, 없으면 빈 목록)
 */
public record MarketFeatures(
    LocalDate asOf,
    List<IndexFeature> indices,
    List<FlowFeature> flows,
    List<GlobalFeature> global,
    List<SectorFeature> topSectors,
    List<SectorFeature> bottomSectors,
    Map<String, Double> sigma5d,
    LocalDate globalAsOf,
    Integer globalAgeTradingDays,
    LocalDate flowAsOf,
    LocalDate sectorAsOf,
    LocalDate entryDate,
    LocalDate exitDate,
    List<MarketTrend> trends,
    List<GlobalLink> links) {

  public record IndexFeature(String code, String name, double close, Double r1, Double r5, Double r20, Double r60, Double distMa20, Double distMa60) {
  }

  /** 시장별 투자자 순매수 금액(원): 1일·5일 합 */
  public record FlowFeature(String market, Long frgn1, Long inst1, Long indi1, Long frgn5, Long inst5, Long indi5) {
  }

  /** 미국 T-1 마감(현지일 < 국내 기준일)의 종가와 1/5/20/60일 수익률 */
  public record GlobalFeature(String symbol, LocalDate date, double close, Double r1, Double r5, Double r20, Double r60) {
  }

  /** 섹터 5일 시총가중 등락 합, 최신일 상승 비율·52주 고점 근접 비율, 외인 5일 순매수 합, 구성 종목 수 */
  public record SectorFeature(String code, String name, Double cw5d, Double rising, Double nearHigh, Long frgn5, int members) {
  }

  /**
   * 지수 코드의 추세 (없으면 null).
   */
  public MarketTrend trendOf(String indexCode) {
    if (trends == null) {
      return null;
    }
    return trends.stream().filter(t -> indexCode.equals(t.indexCode())).findFirst().orElse(null);
  }

  /**
   * 지수 코드 → 추세 라벨 맵 (가드·교훈 태깅용).
   */
  public Map<String, kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode> trendCodes() {
    Map<String, kr.hvy.blog.modules.advisor.domain.code.MarketTrendCode> map = new java.util.LinkedHashMap<>();
    if (trends != null) {
      trends.forEach(t -> map.put(t.indexCode(), t.code()));
    }
    return map;
  }

  /**
   * 관측 기준일 블록 — 프롬프트 dataAsOf 와 tb_advisor_advice.data_as_of_json 이 같은 모양을 쓴다. 수급은 당일 잠정치라 flowProvisional 을 고정 표기.
   */
  public Map<String, Object> dataAsOf() {
    Map<String, Object> block = new java.util.LinkedHashMap<>();
    block.put("domestic", asOf == null ? null : asOf.toString());
    block.put("flow", flowAsOf == null ? null : flowAsOf.toString());
    block.put("flowProvisional", true);
    block.put("sector", sectorAsOf == null ? null : sectorAsOf.toString());
    block.put("global", globalAsOf == null ? null : globalAsOf.toString());
    block.put("globalAgeTradingDays", globalAgeTradingDays);
    return block;
  }
}
