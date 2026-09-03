package kr.hvy.blog.modules.stock.repository;

import java.util.Collection;
import java.util.List;
import kr.hvy.blog.modules.stock.domain.code.CheckpointStatus;
import kr.hvy.blog.modules.stock.domain.entity.CollectCheckpointId;
import kr.hvy.blog.modules.stock.domain.entity.StockCollectCheckpoint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCollectCheckpointRepository extends JpaRepository<StockCollectCheckpoint, CollectCheckpointId> {

  /**
   * 재개 대상(PENDING/IN_PROGRESS/FAILED)을 대상 코드 순으로 조회한다.
   */
  @Query("SELECT c FROM StockCollectCheckpoint c "
      + "WHERE c.id.jobType = :jobType AND c.status IN :statuses "
      + "ORDER BY c.id.targetKey")
  List<StockCollectCheckpoint> findResumable(@Param("jobType") String jobType,
      @Param("statuses") Collection<CheckpointStatus> statuses, Pageable pageable);

  @Query("SELECT c FROM StockCollectCheckpoint c WHERE c.id.jobType = :jobType ORDER BY c.updatedAt DESC")
  List<StockCollectCheckpoint> findRecent(@Param("jobType") String jobType, Pageable pageable);

  @Query("SELECT c FROM StockCollectCheckpoint c "
      + "WHERE c.id.jobType = :jobType AND c.status = :status ORDER BY c.updatedAt DESC")
  List<StockCollectCheckpoint> findRecentByStatus(@Param("jobType") String jobType,
      @Param("status") CheckpointStatus status, Pageable pageable);

  /**
   * 상태별 건수 (진행률 표시용).
   */
  @Query("SELECT c.status AS status, COUNT(c) AS count FROM StockCollectCheckpoint c "
      + "WHERE c.id.jobType = :jobType GROUP BY c.status")
  List<StatusCount> countByStatus(@Param("jobType") String jobType);

  interface StatusCount {

    CheckpointStatus getStatus();

    long getCount();
  }
}
