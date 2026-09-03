package kr.hvy.blog.modules.stock.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCollectRunRepository extends JpaRepository<StockCollectRun, Long> {

  List<StockCollectRun> findAllByOrderByStartedAtDesc(Pageable pageable);

  List<StockCollectRun> findAllByJobTypeOrderByStartedAtDesc(CollectJobType jobType, Pageable pageable);

  Optional<StockCollectRun> findFirstByJobTypeAndStatus(CollectJobType jobType, CollectStatus status);

  /**
   * 카운터를 원자적으로 누적한다. 엔티티를 읽어 더하는 방식은 동시 갱신 시 값을 잃는다.
   */
  @Modifying
  @Query("UPDATE StockCollectRun r "
      + "SET r.rowsUpserted = r.rowsUpserted + :rows, "
      + "    r.apiCallCount = r.apiCallCount + :calls, "
      + "    r.apiFailCount = r.apiFailCount + :fails, "
      + "    r.updated.at = :now "
      + "WHERE r.runId = :runId")
  int addCounters(@Param("runId") Long runId, @Param("rows") long rows, @Param("calls") long calls,
      @Param("fails") long fails, @Param("now") Instant now);

  /**
   * 오래 RUNNING 으로 남은 run 을 FAILED 로 일괄 정리한다 (프로세스 강제 종료 복구).
   */
  @Modifying
  @Query("UPDATE StockCollectRun r "
      + "SET r.status = :failed, r.finishedAt = :now, r.errorMessage = :message, r.updated.at = :now "
      + "WHERE r.status = :running AND r.startedAt < :before")
  int reconcileStale(@Param("running") CollectStatus running, @Param("failed") CollectStatus failed,
      @Param("before") Instant before, @Param("now") Instant now, @Param("message") String message);
}
