package kr.hvy.blog.modules.stock.application.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import kr.hvy.blog.modules.stock.domain.model.AdjustEventRow;
import kr.hvy.blog.modules.stock.domain.model.CorporateActionRow;

/**
 * 기업행사 → 수정주가 계수. 효력일 이전 가격 × price_factor, 거래량 × volume_factor 가 현재 기준 가격이 된다.
 * <ul>
 *   <li>액면교체: price = 변경후/변경전 액면가, volume = 역수 (5000→500 분할이면 0.1 / 10)</li>
 *   <li>무상증자 배정율 r(주당 주수): price = 1/(1+r), volume = 1+r</li>
 *   <li>유상증자 배정율 r·발행가 P·권리락 전일 종가 C: price = (C + P·r) / ((1+r)·C), volume = 1+r (KRX 권리락 기준가 방식)</li>
 *   <li>감자 배정율 r: r&lt;1 이면 잔존 비율로 보아 price = 1/r, volume = r; r&gt;1 이면 병합 비율로 보아 price = r, volume = 1/r</li>
 * </ul>
 * 배정율 단위(주당 주수 vs %)는 예탁원 응답 실측으로 확정해야 하며, 결과는 KIS 수정주가(FID_ORG_ADJ_PRC=0)와 대조해 verified 로 표시한다.
 */
public final class AdjustFactorCalculator {

  static final int SCALE = 10;
  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
  /** 이 범위를 벗어나는 계수는 단위 오해로 보고 만들지 않는다 */
  private static final BigDecimal MIN_FACTOR = new BigDecimal("0.0001");
  private static final BigDecimal MAX_FACTOR = new BigDecimal("10000");

  private AdjustFactorCalculator() {
  }

  /**
   * 기업행사 1건을 계수 이벤트로 바꾼다. 유상증자는 권리락 전일 종가가 필요하다.
   *
   * @param previousClose 유상증자용 권리락 전일 종가 (다른 유형은 무시)
   * @return 해석 불가·의미 없는(계수 1) 행사는 empty
   */
  public static Optional<AdjustEventRow> toEvent(CorporateActionRow action, BigDecimal previousClose) {
    BigDecimal price;
    BigDecimal volume;
    switch (action.actionType()) {
      case SPLIT, REVERSE_SPLIT -> {
        if (!positive(action.ratioBefore()) || !positive(action.ratioAfter())) {
          return Optional.empty();
        }
        price = action.ratioAfter().divide(action.ratioBefore(), MC);
        volume = action.ratioBefore().divide(action.ratioAfter(), MC);
      }
      case BONUS_ISSUE -> {
        if (!positive(action.ratioAfter())) {
          return Optional.empty();
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(action.ratioAfter());
        price = BigDecimal.ONE.divide(onePlusR, MC);
        volume = onePlusR;
      }
      case RIGHTS_ISSUE -> {
        if (!positive(action.ratioAfter()) || !positive(action.cashAmount()) || !positive(previousClose)) {
          return Optional.empty();
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(action.ratioAfter());
        BigDecimal numerator = previousClose.add(action.cashAmount().multiply(action.ratioAfter()));
        price = numerator.divide(onePlusR.multiply(previousClose), MC);
        volume = onePlusR;
      }
      case CAPITAL_REDUCTION -> {
        BigDecimal r = action.ratioAfter();
        if (!positive(r) || r.compareTo(BigDecimal.ONE) == 0) {
          return Optional.empty();
        }
        if (r.compareTo(BigDecimal.ONE) < 0) {
          price = BigDecimal.ONE.divide(r, MC);
          volume = r;
        } else {
          price = r;
          volume = BigDecimal.ONE.divide(r, MC);
        }
      }
      default -> {
        return Optional.empty();
      }
    }
    price = price.setScale(SCALE, RoundingMode.HALF_UP);
    volume = volume.setScale(SCALE, RoundingMode.HALF_UP);
    if (price.compareTo(MIN_FACTOR) < 0 || price.compareTo(MAX_FACTOR) > 0 || price.compareTo(BigDecimal.ONE) == 0) {
      return Optional.empty();
    }
    return Optional.of(new AdjustEventRow(action.ticker(), action.effectiveDate(), action.actionType(), price, volume));
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
  }
}
