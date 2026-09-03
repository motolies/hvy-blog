package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.model.DailyPriceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 종목 일봉(원주가) 배치 upsert.
 * <p>
 * 값이 같은 행은 {@code WHERE … IS DISTINCT FROM} 으로 UPDATE 를 건너뛴다. 매일 최근 100건을 재수집하는
 * 파이프라인에서 이 한 줄이 dead tuple 과 VACUUM 부하를 크게 줄인다.
 */
@Repository
@RequiredArgsConstructor
public class StockDailyPriceWriter {

  static final int PARAMS_PER_ROW = 15;

  private static final String UPSERT_SQL = """
      INSERT INTO tb_stock_daily_price
          (ticker, trade_date, open_price, high_price, low_price, close_price,
           volume, trading_value, prev_diff, prev_diff_sign, change_rate,
           flng_cls_code, prtt_rate, mod_yn, revl_issu_reas, collected_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          open_price     = EXCLUDED.open_price,
          high_price     = EXCLUDED.high_price,
          low_price      = EXCLUDED.low_price,
          close_price    = EXCLUDED.close_price,
          volume         = EXCLUDED.volume,
          trading_value  = EXCLUDED.trading_value,
          prev_diff      = EXCLUDED.prev_diff,
          prev_diff_sign = EXCLUDED.prev_diff_sign,
          change_rate    = EXCLUDED.change_rate,
          flng_cls_code  = EXCLUDED.flng_cls_code,
          prtt_rate      = EXCLUDED.prtt_rate,
          mod_yn         = EXCLUDED.mod_yn,
          revl_issu_reas = EXCLUDED.revl_issu_reas,
          collected_at   = NOW()
      WHERE tb_stock_daily_price.open_price     IS DISTINCT FROM EXCLUDED.open_price
         OR tb_stock_daily_price.high_price     IS DISTINCT FROM EXCLUDED.high_price
         OR tb_stock_daily_price.low_price      IS DISTINCT FROM EXCLUDED.low_price
         OR tb_stock_daily_price.close_price    IS DISTINCT FROM EXCLUDED.close_price
         OR tb_stock_daily_price.volume         IS DISTINCT FROM EXCLUDED.volume
         OR tb_stock_daily_price.trading_value  IS DISTINCT FROM EXCLUDED.trading_value
         OR tb_stock_daily_price.change_rate    IS DISTINCT FROM EXCLUDED.change_rate
         OR tb_stock_daily_price.flng_cls_code  IS DISTINCT FROM EXCLUDED.flng_cls_code
         OR tb_stock_daily_price.prtt_rate      IS DISTINCT FROM EXCLUDED.prtt_rate
         OR tb_stock_daily_price.mod_yn         IS DISTINCT FROM EXCLUDED.mod_yn
      """;

  private final BatchUpsertSupport upsertSupport;

  /**
   * 일봉 행들을 upsert 하고 실제로 삽입·변경된 행 수를 돌려준다.
   */
  public int upsert(List<DailyPriceRow> rows) {
    return upsertSupport.batchUpsert(UPSERT_SQL, rows, PARAMS_PER_ROW, StockDailyPriceWriter::bind);
  }

  private static void bind(PreparedStatement ps, DailyPriceRow row) throws SQLException {
    ps.setString(1, row.ticker());
    ps.setObject(2, row.tradeDate());
    ps.setBigDecimal(3, row.open());
    ps.setBigDecimal(4, row.high());
    ps.setBigDecimal(5, row.low());
    ps.setBigDecimal(6, row.close());
    ps.setLong(7, row.volume());
    ps.setLong(8, row.tradingValue());
    ps.setBigDecimal(9, row.prevDiff());
    ps.setString(10, row.prevDiffSign());
    ps.setBigDecimal(11, row.changeRate());
    ps.setString(12, row.flngClsCode());
    ps.setBigDecimal(13, row.splitRate());
    ps.setString(14, row.modYn());
    ps.setString(15, row.revalReason());
  }
}
