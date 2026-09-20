package kr.hvy.blog.modules.stock.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import kr.hvy.blog.modules.stock.domain.code.CollectStatus;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 수집 run 저장소. 관리자 목록 필터(jobType·status·기간)는 {@link JpaSpecificationExecutor} 로 조합한다 —
 * {@code @Convert} enum 컬럼에 JPQL {@code :p IS NULL OR …} 을 걸면 PG 가 바인딩 타입을 거부하는데 H2 는 통과시켜 테스트가 못 잡는다(2026-09-20).
 */
public interface StockCollectRunRepository extends JpaRepository<StockCollectRun, Long>, JpaSpecificationExecutor<StockCollectRun> {

  /** 잡별 최근 run (advisor ScoreJob 이 최근 WEEKLY 완료 여부를 보는 데 쓴다) */
  List<StockCollectRun> findAllByJobTypeOrderByStartedAtDesc(CollectJobType jobType, Pageable pageable);

  Optional<StockCollectRun> findFirstByJobTypeAndStatus(CollectJobType jobType, CollectStatus status);

  /**
   * 특정 대상일의 가장 최근 종료 run (advisor 게이트가 DAILY 완료·단계 결과를 확인하는 데 쓴다, 2026-09-13).
   */
  Optional<StockCollectRun> findFirstByJobTypeAndTargetDateAndStatusInOrderByStartedAtDesc(CollectJobType jobType, LocalDate targetDate,
      List<CollectStatus> statuses);

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
