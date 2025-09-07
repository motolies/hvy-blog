package kr.hvy.blog.modules.jira.domain.repository;

import kr.hvy.blog.modules.jira.domain.entity.JiraIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Jira 이슈 리포지토리
 */
@Repository
public interface JiraIssueRepository extends JpaRepository<JiraIssue, Long> {

    /**
     * 이슈 키로 이슈 조회
     */
    Optional<JiraIssue> findByIssueKey(String issueKey);

    /**
     * 이슈 키 존재 여부 확인
     */
    boolean existsByIssueKey(String issueKey);

    /**
     * 워크로그와 함께 이슈 조회
     */
    @Query("SELECT i FROM JiraIssue i LEFT JOIN FETCH i.worklogs WHERE i.issueKey = :issueKey")
    Optional<JiraIssue> findByIssueKeyWithWorklogs(@Param("issueKey") String issueKey);
}