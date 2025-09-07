package kr.hvy.blog.modules.jira.domain.repository;

import kr.hvy.blog.modules.jira.domain.entity.JiraWorklog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Jira 워크로그 리포지토리
 */
@Repository
public interface JiraWorklogRepository extends JpaRepository<JiraWorklog, Long> {

    /**
     * 워크로그 ID로 워크로그 조회
     */
    Optional<JiraWorklog> findByWorklogId(String worklogId);

    /**
     * 워크로그 ID 존재 여부 확인
     */
    boolean existsByWorklogId(String worklogId);

    /**
     * 이슈 키로 워크로그 목록 조회
     */
    List<JiraWorklog> findByIssueKeyOrderByStartedDesc(String issueKey);

    /**
     * 특정 기간 동안의 워크로그 조회
     */
    @Query("SELECT w FROM JiraWorklog w WHERE w.started BETWEEN :startDate AND :endDate ORDER BY w.started DESC")
    List<JiraWorklog> findByStartedBetweenOrderByStartedDesc(@Param("startDate") LocalDateTime startDate, 
                                                           @Param("endDate") LocalDateTime endDate);

    /**
     * 작업자별 워크로그 조회
     */
    List<JiraWorklog> findByAuthorOrderByStartedDesc(String author);
}