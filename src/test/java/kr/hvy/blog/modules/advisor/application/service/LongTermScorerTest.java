package kr.hvy.blog.modules.advisor.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import kr.hvy.blog.modules.advisor.domain.code.SignalCode;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.LongTermFactorRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장기 규칙 점수(M8)의 결정론·결측·필터 규칙.
 */
class LongTermScorerTest {

  static final Map<String, Double> WEIGHTS = new LinkedHashMap<>(Map.of("MOM_12_1", 0.30, "QUALITY_ROE", 0.20, "QUALITY_DEBT", 0.15,
      "OP_GROWTH", 0.15, "LOW_VOL_60", 0.20));

  /** 종목 i: 모멘텀 i, ROE i, 부채비율 100−i(낮을수록 좋음 → i 클수록 좋음), 영업이익 증가율 i, 변동성 0.05−i/1000(낮을수록 좋음) — 전 팩터가 i 에 단조 */
  static LongTermFactorRow row(int i, boolean k200, String sector) {
    Map<SignalCode, Double> f = new EnumMap<>(SignalCode.class);
    f.put(SignalCode.MOM_12_1, i / 100.0);
    f.put(SignalCode.QUALITY_ROE, (double) i);
    f.put(SignalCode.QUALITY_DEBT, 100.0 - i);
    f.put(SignalCode.OP_GROWTH, (double) i);
    f.put(SignalCode.LOW_VOL_60, 0.05 - i / 1000.0);
    return LongTermFactorRow.builder().ticker(String.format("T%02d", i)).stockName("종목" + i).marketType("KOSPI").sectorCode(sector)
        .sectorName(sector).kospi200(k200).rawClose(BigDecimal.valueOf(1000)).adjClose(1000.0).factors(f).build();
  }

  static LongTermScorer.Rule rule(int candidates, int picks, int perSector) {
    return new LongTermScorer.Rule(WEIGHTS, false, 0.6, candidates, picks, perSector);
  }

  @Test
  @DisplayName("결정론: 같은 입력이면 입력 행 순서를 섞어도 같은 후보·점수·픽 순위")
  void deterministicUnderShuffle() {
    List<LongTermFactorRow> rows = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      rows.add(row(i, true, "S" + (i % 8)));
    }
    // 동률을 일부 만든다: T38·T39 를 같은 값으로
    rows.set(39, LongTermFactorRow.builder().ticker("T39").stockName("종목39").marketType("KOSPI").sectorCode("S7").kospi200(true)
        .factors(rows.get(38).factors()).build());
    LongTermScorer.Ranking base = LongTermScorer.rank(rows, rule(30, 10, 3));
    for (long seed = 1; seed <= 5; seed++) {
      List<LongTermFactorRow> shuffled = new ArrayList<>(rows);
      Collections.shuffle(shuffled, new Random(seed));
      LongTermScorer.Ranking again = LongTermScorer.rank(shuffled, rule(30, 10, 3));
      assertThat(again.pickTickers()).isEqualTo(base.pickTickers());
      assertThat(again.candidates()).extracting(CandidateRow::ticker).isEqualTo(base.candidates().stream().map(CandidateRow::ticker).toList());
      assertThat(again.candidates()).extracting(CandidateRow::quantScore).isEqualTo(base.candidates().stream().map(CandidateRow::quantScore).toList());
    }
    assertThat(base.pickTickers()).first().as("전 팩터 최상위(동률 T38·T39 는 티커 순)").isEqualTo("T38");
    assertThat(base.pickTickers()).hasSize(10);
    assertThat(base.candidates()).extracting(CandidateRow::quantRank).startsWith(1, 2, 3);
  }

  @Test
  @DisplayName("부호: 낮을수록 좋은 팩터(부채비율·변동성)는 뒤집힌다 — 전 팩터가 i 에 단조면 점수도 i 에 단조, 최상위 점수 1.0·최하위 −1.0")
  void lowerIsBetterSign() {
    List<LongTermFactorRow> rows = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      rows.add(row(i, true, "S" + i));
    }
    LongTermScorer.Ranking r = LongTermScorer.rank(rows, rule(11, 5, 3));
    assertThat(r.candidates()).extracting(CandidateRow::ticker).startsWith("T10", "T09", "T08");
    assertThat(r.candidates().getFirst().quantScore()).isEqualTo(1.0);
    assertThat(r.candidates().getLast().quantScore()).isEqualTo(-1.0);
    assertThat(r.candidates().getFirst().signals().get("QUALITY_DEBT").pct()).as("백분위는 원값 기준(뒤집기 전) — 부채 최저").isEqualTo(0.0);
    assertThat(r.candidates().getFirst().signals().get("QUALITY_DEBT").raw()).isEqualTo(90.0);
  }

  @Test
  @DisplayName("결측: 팩터가 없으면 중립(기여 0)이고, 값 있는 가중치 비율이 min-coverage 미만이면 후보에서 뺀다 — 재무 3종(0.50)이 없으면 0.50 < 0.6 제외")
  void missingFactorsAndCoverage() {
    List<LongTermFactorRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(row(i, true, "S" + i));
    }
    // T09: 재무 3종 결측(커버리지 0.50) → 제외. T08: ROE 만 결측(커버리지 0.80) → 남되 ROE 기여 0
    Map<SignalCode, Double> noFin = new EnumMap<>(rows.get(9).factors());
    noFin.put(SignalCode.QUALITY_ROE, null);
    noFin.put(SignalCode.QUALITY_DEBT, null);
    noFin.put(SignalCode.OP_GROWTH, null);
    rows.set(9, LongTermFactorRow.builder().ticker("T09").marketType("KOSPI").sectorCode("S9").kospi200(true).factors(noFin).build());
    Map<SignalCode, Double> noRoe = new EnumMap<>(rows.get(8).factors());
    noRoe.put(SignalCode.QUALITY_ROE, null);
    rows.set(8, LongTermFactorRow.builder().ticker("T08").marketType("KOSPI").sectorCode("S8").kospi200(true).factors(noRoe).build());

    LongTermScorer.Ranking r = LongTermScorer.rank(rows, rule(10, 5, 3));
    assertThat(r.candidates()).extracting(CandidateRow::ticker).doesNotContain("T09");
    assertThat(r.eligibleSize()).isEqualTo(9);
    CandidateRow t08 = r.candidates().stream().filter(c -> c.ticker().equals("T08")).findFirst().orElseThrow();
    assertThat(t08.features()).containsEntry("coverage", 0.8);
    assertThat(t08.signals().get("QUALITY_ROE").raw()).isNull();
    assertThat(t08.signals().get("QUALITY_ROE").pct()).as("결측은 스냅샷에 0.5(중립)").isEqualTo(0.5);
    // ROE 결측 종목은 ROE 백분위 모집단에서도 빠진다 — T07 이 ROE 최상위(1.0)
    CandidateRow t07 = r.candidates().stream().filter(c -> c.ticker().equals("T07")).findFirst().orElseThrow();
    assertThat(t07.signals().get("QUALITY_ROE").pct()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("KOSPI200 필터는 백분위를 매긴 뒤 후보에만 — 비구성 종목도 모집단에 남아 척도가 같고, ALL 이면 필터 없음")
  void pickUniverseFilterAfterPercentile() {
    List<LongTermFactorRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(row(i, i % 2 == 0, "S" + i));
    }
    LongTermScorer.Ranking k200 = LongTermScorer.rank(rows, rule(10, 3, 3));
    assertThat(k200.universeSize()).isEqualTo(10);
    assertThat(k200.candidates()).extracting(CandidateRow::ticker).containsExactly("T08", "T06", "T04", "T02", "T00");
    assertThat(k200.candidates().getFirst().signals().get("MOM_12_1").pct()).as("모집단 10종목 중 8/9").isEqualTo(0.8889);
    assertThat(k200.pickTickers()).containsExactly("T08", "T06", "T04");

    LongTermScorer.Ranking all = LongTermScorer.rank(rows, new LongTermScorer.Rule(WEIGHTS, true, 0.6, 10, 3, 3));
    assertThat(all.pickTickers()).containsExactly("T09", "T08", "T07");
  }

  @Test
  @DisplayName("섹터 상한: 같은 섹터는 max-per-sector 까지만 후보(섹터 없음은 한 그룹)")
  void sectorCap() {
    List<LongTermFactorRow> rows = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      rows.add(row(i, true, i >= 5 ? "A" : null));
    }
    LongTermScorer.Ranking r = LongTermScorer.rank(rows, rule(10, 4, 2));
    assertThat(r.candidates()).extracting(CandidateRow::ticker).containsExactly("T09", "T08", "T04", "T03");
    assertThat(r.pickTickers()).containsExactly("T09", "T08", "T04", "T03");
    assertThat(r.candidates().getFirst().benchIndexCode()).isEqualTo("0001");
  }

  @Test
  @DisplayName("PERCENT_RANK 동률: 같은 값은 가장 앞 순위를 공유하고 표본 1개면 0")
  void percentRankTies() {
    LongTermFactorRow twin = LongTermFactorRow.builder().ticker("T98").marketType("KOSPI").kospi200(true).factors(row(1, true, "B").factors()).build();
    List<LongTermFactorRow> rows = List.of(row(1, true, "A"), twin, row(3, true, "C"));
    Map<String, Double> p = LongTermScorer.percentRanks(rows, SignalCode.MOM_12_1);
    assertThat(p).containsEntry("T01", 0.0).containsEntry("T98", 0.0).containsEntry("T03", 1.0);
    assertThat(LongTermScorer.percentRanks(List.of(row(5, true, "A")), SignalCode.MOM_12_1)).containsEntry("T05", 0.0);
  }
}
