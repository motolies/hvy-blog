package kr.hvy.blog.modules.stock.repository.jdbc;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 파생 MV(db/stock-derived.sql) 갱신·조회. MV 는 Flyway 없이 psql 로 만들므로 존재 여부를 먼저 확인한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DerivedViewRefresher {

  public static final String MV_ADJUST_FACTOR = "mv_stock_adjust_factor";
  public static final String VW_PRICE_ADJ = "vw_stock_daily_price_adj";
  public static final String TB_DAILY_METRIC = "tb_stock_daily_metric";
  /** work_mem 값 형식 (SET 문에 그대로 들어가므로 숫자+단위만 허용) */
  private static final Pattern WORK_MEM = Pattern.compile("^[1-9][0-9]*(kB|MB|GB)$");
  /** "전체" 를 뜻하는 하한 */
  private static final LocalDate EPOCH = LocalDate.of(1900, 1, 1);

  /**
   * 종목 일별 지표 계산식의 단일 출처. 옛 mv_stock_daily_metric 정의와 같고, 입력 하한(?1)과 기록 하한(?2)만 추가됐다.
   * 값이 같은 행은 IS DISTINCT FROM 으로 UPDATE 를 건너뛴다.
   */
  static final String DAILY_METRIC_UPSERT_SQL = """
      INSERT INTO tb_stock_daily_metric (ticker, trade_date, adj_close, ret_1d, ret_5d, ret_20d, ret_60d, ret_120d, ma_5, ma_20, ma_60, ma_120, dist_ma20, dist_ma60, high_52w, dist_high_52w, tv_avg_5d, tv_avg_60d, tv_ratio_5_60, vol_avg_20d, foreign_net_5d, institution_net_5d, computed_at)
      SELECT ticker, trade_date, adj_close, ret_1d, ret_5d, ret_20d, ret_60d, ret_120d, ma_5, ma_20, ma_60, ma_120, dist_ma20, dist_ma60, high_52w, dist_high_52w, tv_avg_5d, tv_avg_60d, tv_ratio_5_60, vol_avg_20d, foreign_net_5d, institution_net_5d, NOW()
      FROM (
        WITH base AS (
            SELECT a.ticker,
                   a.trade_date,
                   a.adj_close,
                   a.adj_high,
                   a.adj_volume,
                   a.trading_value::double precision AS trading_value,
                   i.foreign_net_amt::double precision     AS foreign_net_amt,
                   i.institution_net_amt::double precision AS institution_net_amt
            FROM vw_stock_daily_price_adj a
                     LEFT JOIN tb_stock_investor_daily i ON i.ticker = a.ticker AND i.trade_date = a.trade_date
            WHERE a.trade_date >= ?
        )
        SELECT ticker,
               trade_date,
               adj_close,
               adj_close / NULLIF(LAG(adj_close, 1) OVER w, 0) - 1                                          AS ret_1d,
               adj_close / NULLIF(LAG(adj_close, 5) OVER w, 0) - 1                                          AS ret_5d,
               adj_close / NULLIF(LAG(adj_close, 20) OVER w, 0) - 1                                         AS ret_20d,
               adj_close / NULLIF(LAG(adj_close, 60) OVER w, 0) - 1                                         AS ret_60d,
               adj_close / NULLIF(LAG(adj_close, 120) OVER w, 0) - 1                                        AS ret_120d,
               AVG(adj_close) OVER (w ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)                              AS ma_5,
               AVG(adj_close) OVER (w ROWS BETWEEN 19 PRECEDING AND CURRENT ROW)                             AS ma_20,
               AVG(adj_close) OVER (w ROWS BETWEEN 59 PRECEDING AND CURRENT ROW)                             AS ma_60,
               AVG(adj_close) OVER (w ROWS BETWEEN 119 PRECEDING AND CURRENT ROW)                            AS ma_120,
               adj_close / NULLIF(AVG(adj_close) OVER (w ROWS BETWEEN 19 PRECEDING AND CURRENT ROW), 0) - 1  AS dist_ma20,
               adj_close / NULLIF(AVG(adj_close) OVER (w ROWS BETWEEN 59 PRECEDING AND CURRENT ROW), 0) - 1  AS dist_ma60,
               MAX(adj_high) OVER (w ROWS BETWEEN 251 PRECEDING AND CURRENT ROW)                             AS high_52w,
               adj_close / NULLIF(MAX(adj_high) OVER (w ROWS BETWEEN 251 PRECEDING AND CURRENT ROW), 0) - 1  AS dist_high_52w,
               AVG(trading_value) OVER (w ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)                          AS tv_avg_5d,
               AVG(trading_value) OVER (w ROWS BETWEEN 59 PRECEDING AND CURRENT ROW)                         AS tv_avg_60d,
               AVG(trading_value) OVER (w ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)
                   / NULLIF(AVG(trading_value) OVER (w ROWS BETWEEN 59 PRECEDING AND CURRENT ROW), 0)        AS tv_ratio_5_60,
               AVG(adj_volume) OVER (w ROWS BETWEEN 19 PRECEDING AND CURRENT ROW)                            AS vol_avg_20d,
               SUM(foreign_net_amt) OVER (w ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)                        AS foreign_net_5d,
               SUM(institution_net_amt) OVER (w ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)                    AS institution_net_5d
        FROM base
        WINDOW w AS (PARTITION BY ticker ORDER BY trade_date)
      ) m
      WHERE m.trade_date >= ?
      ON CONFLICT (ticker, trade_date) DO UPDATE SET
          adj_close          = EXCLUDED.adj_close,
          ret_1d             = EXCLUDED.ret_1d,
          ret_5d             = EXCLUDED.ret_5d,
          ret_20d            = EXCLUDED.ret_20d,
          ret_60d            = EXCLUDED.ret_60d,
          ret_120d           = EXCLUDED.ret_120d,
          ma_5               = EXCLUDED.ma_5,
          ma_20              = EXCLUDED.ma_20,
          ma_60              = EXCLUDED.ma_60,
          ma_120             = EXCLUDED.ma_120,
          dist_ma20          = EXCLUDED.dist_ma20,
          dist_ma60          = EXCLUDED.dist_ma60,
          high_52w           = EXCLUDED.high_52w,
          dist_high_52w      = EXCLUDED.dist_high_52w,
          tv_avg_5d          = EXCLUDED.tv_avg_5d,
          tv_avg_60d         = EXCLUDED.tv_avg_60d,
          tv_ratio_5_60      = EXCLUDED.tv_ratio_5_60,
          vol_avg_20d        = EXCLUDED.vol_avg_20d,
          foreign_net_5d     = EXCLUDED.foreign_net_5d,
          institution_net_5d = EXCLUDED.institution_net_5d,
          computed_at        = NOW()
      WHERE (tb_stock_daily_metric.adj_close, tb_stock_daily_metric.ret_1d, tb_stock_daily_metric.ret_5d, tb_stock_daily_metric.ret_20d, tb_stock_daily_metric.ret_60d, tb_stock_daily_metric.ret_120d, tb_stock_daily_metric.ma_5, tb_stock_daily_metric.ma_20, tb_stock_daily_metric.ma_60, tb_stock_daily_metric.ma_120, tb_stock_daily_metric.dist_ma20, tb_stock_daily_metric.dist_ma60, tb_stock_daily_metric.high_52w, tb_stock_daily_metric.dist_high_52w, tb_stock_daily_metric.tv_avg_5d, tb_stock_daily_metric.tv_avg_60d, tb_stock_daily_metric.tv_ratio_5_60, tb_stock_daily_metric.vol_avg_20d, tb_stock_daily_metric.foreign_net_5d, tb_stock_daily_metric.institution_net_5d)
            IS DISTINCT FROM (EXCLUDED.adj_close, EXCLUDED.ret_1d, EXCLUDED.ret_5d, EXCLUDED.ret_20d, EXCLUDED.ret_60d, EXCLUDED.ret_120d, EXCLUDED.ma_5, EXCLUDED.ma_20, EXCLUDED.ma_60, EXCLUDED.ma_120, EXCLUDED.dist_ma20, EXCLUDED.dist_ma60, EXCLUDED.high_52w, EXCLUDED.dist_high_52w, EXCLUDED.tv_avg_5d, EXCLUDED.tv_avg_60d, EXCLUDED.tv_ratio_5_60, EXCLUDED.vol_avg_20d, EXCLUDED.foreign_net_5d, EXCLUDED.institution_net_5d)
      """;

  private final JdbcTemplate jdbcTemplate;

  /** 수정주가 뷰 1행 */
  public record AdjustedClose(LocalDate tradeDate, BigDecimal adjClose, BigDecimal adjVolume, BigDecimal rawClose) {
  }

  /**
   * MV 존재 여부 (pg_matviews).
   */
  public boolean materializedViewExists(String name) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM pg_matviews WHERE schemaname = current_schema() AND matviewname = ?", Integer.class, name);
    return count != null && count > 0;
  }

  /**
   * MV 를 세션 기본 work_mem 으로 갱신한다 (테스트·소형 MV 용).
   *
   * @return 소요 ms
   */
  public long refresh(String name, boolean concurrently) {
    return refresh(name, concurrently, null, null);
  }

  /**
   * MV 를 갱신한다. CONCURRENTLY 는 유니크 인덱스가 있고 한 번 채워진 MV 에만 가능하다.
   * workMem 이 있으면 같은 커넥션에서 SET work_mem 후 REFRESH 하고 끝나면 RESET 한다 — CONCURRENTLY 는 트랜잭션 블록 안에서
   * 못 돌므로 SET LOCAL 대신 세션 SET 을 쓰고, 풀에 반환되기 전에 반드시 되돌린다.
   *
   * @return 소요 ms
   */
  public long refresh(String name, boolean concurrently, String workMem) {
    return refresh(name, concurrently, workMem, null);
  }

  /**
   * 세션 설정을 지정해 MV 를 갱신한다. maxParallelWorkers=0 이면 병렬 해시 조인이 /dev/shm 공유 메모리를 잡지 않아
   * Docker 기본 shm(64MB) 에서도 안전하다. 설정은 같은 커넥션에서 SET → REFRESH → RESET 한다.
   */
  public long refresh(String name, boolean concurrently, String workMem, Integer maxParallelWorkers) {
    String sql = "REFRESH MATERIALIZED VIEW " + (concurrently ? "CONCURRENTLY " : "") + name;
    long started = System.currentTimeMillis();
    withSession(workMem, maxParallelWorkers, statement -> {
      statement.execute(sql);
      return null;
    });
    long elapsed = System.currentTimeMillis() - started;
    log.info("MV 갱신: {} concurrently={} workMem={} parallel={} {}ms", name, concurrently, workMem, maxParallelWorkers, elapsed);
    return elapsed;
  }

  /**
   * 종목 일별 지표 테이블을 다시 계산해 upsert 한다. from 이 null 이면 전체, 아니면 trade_date >= from 행만 쓴다.
   * 창 함수 프레임(최대 252행)이 잘리지 않도록 입력은 lookbackStart(from 보다 충분히 앞) 부터 읽는다.
   *
   * @return 삽입·변경된 행 수
   */
  public int recomputeDailyMetric(LocalDate from, LocalDate lookbackStart, String workMem, Integer maxParallelWorkers) {
    LocalDate inputFrom = lookbackStart == null ? EPOCH : lookbackStart;
    LocalDate writeFrom = from == null ? EPOCH : from;
    long started = System.currentTimeMillis();
    int rows = withSession(workMem, maxParallelWorkers, statement -> {
      try (PreparedStatement ps = statement.getConnection().prepareStatement(DAILY_METRIC_UPSERT_SQL)) {
        ps.setObject(1, inputFrom);
        ps.setObject(2, writeFrom);
        return ps.executeUpdate();
      }
    });
    log.info("종목 일별 지표 재계산: from={}, lookback={}, rows={}, {}ms", from, lookbackStart, rows, System.currentTimeMillis() - started);
    return rows;
  }

  /**
   * 테이블 존재 여부 (information_schema.tables).
   */
  public boolean tableExists(String name) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?", Integer.class, name);
    return count != null && count > 0;
  }

  /**
   * 같은 커넥션에서 세션 설정(work_mem·병렬 워커)을 SET 하고 작업을 실행한 뒤 RESET 한다.
   * CONCURRENTLY REFRESH 는 트랜잭션 블록 안에서 못 돌므로 SET LOCAL 대신 세션 SET 을 쓰고, 풀에 반환되기 전에 반드시 되돌린다.
   */
  private <T> T withSession(String workMem, Integer maxParallelWorkers, SessionWork<T> work) {
    if (workMem != null && !WORK_MEM.matcher(workMem).matches()) {
      throw new IllegalArgumentException("work_mem 형식 오류: " + workMem + " (예: 512MB)");
    }
    if (maxParallelWorkers != null && (maxParallelWorkers < 0 || maxParallelWorkers > 64)) {
      throw new IllegalArgumentException("max_parallel_workers_per_gather 범위 오류: " + maxParallelWorkers);
    }
    return jdbcTemplate.execute((java.sql.Connection connection) -> {
      try (Statement statement = connection.createStatement()) {
        if (workMem != null) {
          statement.execute("SET work_mem TO '" + workMem + "'");
        }
        if (maxParallelWorkers != null) {
          statement.execute("SET max_parallel_workers_per_gather TO " + maxParallelWorkers);
        }
        try {
          return work.run(statement);
        } finally {
          if (workMem != null) {
            statement.execute("RESET work_mem");
          }
          if (maxParallelWorkers != null) {
            statement.execute("RESET max_parallel_workers_per_gather");
          }
        }
      }
    });
  }

  /** 세션 설정이 적용된 Statement 로 실행할 작업 */
  @FunctionalInterface
  private interface SessionWork<T> {
    T run(Statement statement) throws SQLException;
  }

  /**
   * 수정주가 뷰에서 종목의 [from, to] 구간을 읽는다.
   */
  public List<AdjustedClose> adjustedCloses(String ticker, LocalDate from, LocalDate to) {
    return jdbcTemplate.query(
        "SELECT trade_date, adj_close, adj_volume, raw_close FROM " + VW_PRICE_ADJ
            + " WHERE ticker = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
        (rs, i) -> new AdjustedClose(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getBigDecimal(3),
            rs.getBigDecimal(4)), ticker, from, to);
  }

  /**
   * 뷰(일반 뷰) 존재 여부.
   */
  public boolean viewExists(String name) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.views WHERE table_schema = current_schema() AND table_name = ?",
        Integer.class, name);
    return count != null && count > 0;
  }
}
