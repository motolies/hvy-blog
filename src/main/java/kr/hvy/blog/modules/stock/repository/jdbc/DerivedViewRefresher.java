package kr.hvy.blog.modules.stock.repository.jdbc;

import java.math.BigDecimal;
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
  /** work_mem 값 형식 (SET 문에 그대로 들어가므로 숫자+단위만 허용) */
  private static final Pattern WORK_MEM = Pattern.compile("^[1-9][0-9]*(kB|MB|GB)$");

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
    if (workMem != null && !WORK_MEM.matcher(workMem).matches()) {
      throw new IllegalArgumentException("work_mem 형식 오류: " + workMem + " (예: 512MB)");
    }
    if (maxParallelWorkers != null && (maxParallelWorkers < 0 || maxParallelWorkers > 64)) {
      throw new IllegalArgumentException("max_parallel_workers_per_gather 범위 오류: " + maxParallelWorkers);
    }
    String sql = "REFRESH MATERIALIZED VIEW " + (concurrently ? "CONCURRENTLY " : "") + name;
    long started = System.currentTimeMillis();
    jdbcTemplate.execute((java.sql.Connection connection) -> {
      try (Statement statement = connection.createStatement()) {
        if (workMem != null) {
          statement.execute("SET work_mem TO '" + workMem + "'");
        }
        if (maxParallelWorkers != null) {
          statement.execute("SET max_parallel_workers_per_gather TO " + maxParallelWorkers);
        }
        try {
          statement.execute(sql);
        } finally {
          if (workMem != null) {
            statement.execute("RESET work_mem");
          }
          if (maxParallelWorkers != null) {
            statement.execute("RESET max_parallel_workers_per_gather");
          }
        }
      }
      return null;
    });
    long elapsed = System.currentTimeMillis() - started;
    log.info("MV 갱신: {} concurrently={} workMem={} parallel={} {}ms", name, concurrently, workMem, maxParallelWorkers, elapsed);
    return elapsed;
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
