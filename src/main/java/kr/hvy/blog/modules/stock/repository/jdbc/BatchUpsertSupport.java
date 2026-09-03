package kr.hvy.blog.modules.stock.repository.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL {@code INSERT … ON CONFLICT DO UPDATE} 배치 실행 공통 지원.
 * <p>
 * 시계열 테이블은 JPA 대신 JdbcTemplate 으로 쓴다. 감사 컬럼이 불필요하고, 영속성 컨텍스트에 수만 건을
 * 올리면 flush 비용과 메모리가 감당되지 않는다. 이 테이블들에는 엔티티가 없으므로 ddl-auto=validate 대상도 아니다.
 * <p>
 * 청크 크기는 PostgreSQL 와이어 프로토콜의 바인드 파라미터 상한(65,535)을 행×컬럼으로 나눠 정한다.
 * JDBC 배치는 문장 단위로 전송되어 보통 이 상한에 걸리지 않지만, 드라이버의 reWriteBatchedInserts 가
 * 켜지면 다중 VALUES 로 재작성되므로 안전하게 유지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchUpsertSupport {

  static final int PG_MAX_PARAMS = 65_535;
  static final int PARAM_MARGIN = 500;
  static final int DEFAULT_CHUNK_SIZE = 1_000;

  private final JdbcTemplate jdbcTemplate;

  /**
   * 행 단위 바인더. PreparedStatement 에 항목 1건의 파라미터를 채운다.
   */
  @FunctionalInterface
  public interface BatchBinder<T> {

    void bind(PreparedStatement ps, T item) throws SQLException;
  }

  /**
   * 지정 SQL 로 배치 upsert 를 수행하고 영향 행 수 합계를 돌려준다.
   * upsert 의 {@code WHERE … IS DISTINCT FROM} 로 건너뛴 행은 0 으로 집계되므로 "실제로 바뀐 행 수"가 된다.
   *
   * @param paramsPerRow 행당 바인드 파라미터 수 (청크 크기 산정용)
   */
  public <T> int batchUpsert(String sql, List<T> items, int paramsPerRow, BatchBinder<T> binder) {
    if (items == null || items.isEmpty()) {
      return 0;
    }
    int chunkSize = chunkSize(paramsPerRow);
    int affected = 0;
    for (int from = 0; from < items.size(); from += chunkSize) {
      List<T> chunk = items.subList(from, Math.min(items.size(), from + chunkSize));
      int[] counts = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
          binder.bind(ps, chunk.get(i));
        }

        @Override
        public int getBatchSize() {
          return chunk.size();
        }
      });
      for (int count : counts) {
        // SUCCESS_NO_INFO(-2) 는 드라이버가 건수를 모르는 경우다. 과대 집계보다 과소 집계가 낫다
        affected += Math.max(count, 0);
      }
    }
    log.debug("batch upsert 완료: rows={}, affected={}, chunkSize={}", items.size(), affected, chunkSize);
    return affected;
  }

  /**
   * 파라미터 상한과 기본 청크 크기 중 작은 쪽을 택한다.
   */
  static int chunkSize(int paramsPerRow) {
    int byParams = (PG_MAX_PARAMS - PARAM_MARGIN) / Math.max(1, paramsPerRow);
    return Math.max(1, Math.min(DEFAULT_CHUNK_SIZE, byParams));
  }
}
