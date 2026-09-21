package kr.hvy.blog.modules.advisor.application.service;

import kr.hvy.blog.modules.advisor.domain.code.PickDirection;
import kr.hvy.blog.modules.advisor.domain.code.PickNoteClass;

/**
 * 12:00 픽 편차의 정량("얼마나")과 결정론 분류("어떻게"). 순수 함수만 두어 IntradayCheckJob 에서 분리해 단위 테스트한다(note-v1, 2026-09-21).
 * <ul>
 *   <li>sinceOpen = 현재가/시가 − 1 — 픽 진입 규약(D+1 시가)과 정합하는 1차 지표. gap = 시가/기준가 − 1 은 기록만(MORNING 축)</li>
 *   <li>excess: 지수 시가가 있으면 OPEN 기준 sinceOpen − benchSinceOpen, 없으면 PREV_CLOSE 기준 (changeRate − benchRate)/100 으로 폴백</li>
 *   <li>σ: OPEN 기준은 vol20 × √(3/6.5) — 일 σ 를 개장 후 3시간 스케일로, PREV_CLOSE 기준은 vol20 (하루 스케일, 보수적). z = excess/σ, zMove = move/σ</li>
 *   <li>classify: |z| 가 임계 미만이어도 픽 자체가 −임계σ 이상 빠졌으면(지수와 같이 빠짐) MARKET_DRAG, 아니면 FLAT. AVOID 는 부호 반전.
 *       지수 대비 뒤처진 하락은 "하락분의 절반 이상이 지수로 설명" 이면 MARKET_DRAG</li>
 * </ul>
 * 모든 비율은 소수(0.012 = 1.2%)이고 KIS 전일 대비율(%)은 여기서 /100 한다.
 */
final class PickDeviation {

  /** 초과 기준: 당일 시가 */
  static final String BASIS_OPEN = "OPEN";
  /** 초과 기준: 전일 종가 (지수 시가가 없을 때 폴백) */
  static final String BASIS_PREV_CLOSE = "PREV_CLOSE";
  /** 개장(09:00)→12:00 3시간 / 정규장 6.5시간 — 일 σ 를 반나절 σ 로 줄이는 시간 스케일 √(3/6.5) */
  static final double HALF_DAY_SCALE = Math.sqrt(3.0 / 6.5);

  /** 지수 대비 초과(소수)와 그 기준 */
  record Excess(double value, String basis) {
  }

  private PickDeviation() {
  }

  /**
   * 현재가/시가 − 1. 시가가 없거나 0 이하면 null.
   */
  static Double sinceOpen(Double current, Double open) {
    return ratio(current, open);
  }

  /**
   * 시가/기준가(전일 종가, 권리락 반영) − 1. 기록 전용.
   */
  static Double gap(Double open, Double prevClose) {
    return ratio(open, prevClose);
  }

  /**
   * 지수 대비 초과. 픽·지수 모두 시가 대비가 있으면 OPEN, 아니면 전일 대비율(%) 차이를 소수로 바꿔 PREV_CLOSE. 둘 다 못 만들면 null.
   */
  static Excess excess(Double sinceOpen, Double benchSinceOpen, Double changeRatePct, Double benchRatePct) {
    if (sinceOpen != null && benchSinceOpen != null) {
      return new Excess(sinceOpen - benchSinceOpen, BASIS_OPEN);
    }
    if (changeRatePct != null && benchRatePct != null) {
      return new Excess((changeRatePct - benchRatePct) / 100.0, BASIS_PREV_CLOSE);
    }
    return null;
  }

  /**
   * 기준에 맞는 σ: OPEN 은 vol20 × √(3/6.5)(반나절), PREV_CLOSE 는 vol20(하루). vol20 이 없거나 0 이하면 null.
   */
  static Double sigma(String basis, Double vol20) {
    if (basis == null || vol20 == null || vol20 <= 0 || Double.isNaN(vol20)) {
      return null;
    }
    return BASIS_OPEN.equals(basis) ? vol20 * HALF_DAY_SCALE : vol20;
  }

  /**
   * 초과를 후보 vol20(ret_1d σ, 소수)으로 정규화한 z = excess / σ(basis). vol20 이 없으면 null.
   */
  static Double z(Excess excess, Double vol20) {
    return excess == null ? null : zOf(excess.value(), excess.basis(), vol20);
  }

  /**
   * 임의 등락(소수)을 같은 σ(basis) 로 정규화한다 — 픽 자체 등락의 zMove 계산용. 값·σ 가 없으면 null.
   */
  static Double zOf(Double value, String basis, Double vol20) {
    Double s = sigma(basis, vol20);
    return value == null || s == null ? null : value / s;
  }

  /**
   * 결정론 분류. move 는 excess 와 같은 기준의 픽 자체 등락(OPEN 이면 sinceOpen, PREV_CLOSE 면 changeRate/100), zMove = move/σ.
   * <pre>
   * s = LONG ? +1 : −1,  zs = z·s,  moveS = move·s,  zMoveS = zMove·s
   * |zs| < 임계 ∧ zMoveS ≤ −임계   → MARKET_DRAG   (지수와 같이 −임계σ 이상 빠짐 — 초과는 0 이라도 회고한다)
   * |zs| < 임계                    → FLAT
   * zs ≥ 2×임계                    → OVERSHOOT
   * zs ≥ 임계                      → ON_TRACK
   * zs ≤ −임계 ∧ moveS < 0 ∧ |excess| ≤ |move|/2 → MARKET_DRAG   (하락분의 절반 이상 = move − excess 가 지수 몫)
   * 그 밖에                        → IDIOSYNCRATIC
   * </pre>
   * z 또는 move 가 없으면(vol20 결손·시가 결손) FLAT — 회고를 생략한다. zMove 가 없으면 첫 규칙만 건너뛴다.
   */
  static PickNoteClass classify(PickDirection direction, Double move, Double excess, Double z, Double zMove, double zThreshold) {
    if (z == null || move == null || excess == null || Double.isNaN(z)) {
      return PickNoteClass.FLAT;
    }
    double s = direction == PickDirection.AVOID ? -1 : 1;
    double zs = z * s;
    double moveS = move * s;
    if (Math.abs(zs) < zThreshold) {
      return zMove != null && !Double.isNaN(zMove) && zMove * s <= -zThreshold ? PickNoteClass.MARKET_DRAG : PickNoteClass.FLAT;
    }
    if (zs >= 2 * zThreshold) {
      return PickNoteClass.OVERSHOOT;
    }
    if (zs > 0) {
      return PickNoteClass.ON_TRACK;
    }
    return moveS < 0 && Math.abs(excess) <= Math.abs(move) / 2 ? PickNoteClass.MARKET_DRAG : PickNoteClass.IDIOSYNCRATIC;
  }

  private static Double ratio(Double numerator, Double denominator) {
    if (numerator == null || denominator == null || denominator <= 0) {
      return null;
    }
    return numerator / denominator - 1;
  }
}
