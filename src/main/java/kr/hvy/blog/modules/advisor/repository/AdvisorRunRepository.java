package kr.hvy.blog.modules.advisor.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorJobType;
import kr.hvy.blog.modules.advisor.domain.code.AdvisorStatus;
import kr.hvy.blog.modules.advisor.domain.entity.AdvisorRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * advisor run 저장소. 관리자 목록 필터는 StockCollectRunRepository 와 같은 이유로 {@link JpaSpecificationExecutor} 를 쓴다(2026-09-20).
 */
public interface AdvisorRunRepository extends JpaRepository<AdvisorRun, Long>, JpaSpecificationExecutor<AdvisorRun> {

  Optional<AdvisorRun> findFirstByJobTypeAndStatus(AdvisorJobType jobType, AdvisorStatus status);

  Optional<AdvisorRun> findFirstByJobTypeAndBaseDateAndStatusInOrderByStartedAtDesc(AdvisorJobType jobType, LocalDate baseDate,
      List<AdvisorStatus> statuses);

  /**
   * 오래 RUNNING 으로 남은 run 을 FAILED 로 일괄 정리한다 (프로세스 강제 종료 복구).
   */
  @Modifying
  @Query("UPDATE AdvisorRun r "
      + "SET r.status = :failed, r.finishedAt = :now, r.errorMessage = :message, r.updated.at = :now "
      + "WHERE r.status = :running AND r.startedAt < :before")
  int reconcileStale(@Param("running") AdvisorStatus running, @Param("failed") AdvisorStatus failed,
      @Param("before") Instant before, @Param("now") Instant now, @Param("message") String message);
}
