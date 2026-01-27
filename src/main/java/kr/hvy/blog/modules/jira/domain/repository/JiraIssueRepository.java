package kr.hvy.blog.modules.jira.domain.repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.jira.domain.entity.JiraIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /**
     * 특정 연도의 스프린트에 해당하는 이슈들 조회 (완료된 이슈만, 담당자와 스토리포인트가 있는 것만)
     */
    @Query("SELECT i FROM JiraIssue i WHERE i.sprint LIKE :yearPattern " +
        "AND i.assignee IS NOT NULL AND i.assignee != '' " +
        "AND i.storyPoints IS NOT NULL AND i.storyPoints > 0 " +
        "AND i.status IN (:statuses)")
    List<JiraIssue> findCompletedIssuesByYear(@Param("yearPattern") String yearPattern, @Param("statuses") Set<String> statuses);

    /**
     * 특정 스프린트의 이슈들을 조회 (워크로그와 함께)
     */
    @Query("SELECT i FROM JiraIssue i LEFT JOIN FETCH i.worklogs WHERE i.sprint = :sprint")
    List<JiraIssue> findBySprint(@Param("sprint") String sprint);

    /**
     * 특정 스프린트와 작업자의 이슈들을 조회 (워크로그와 함께)
     */
    @Query("SELECT i FROM JiraIssue i LEFT JOIN FETCH i.worklogs WHERE i.sprint = :sprint AND i.assignee = :assignee")
    List<JiraIssue> findBySprintAndAssignee(@Param("sprint") String sprint, @Param("assignee") String assignee);

    /**
     * 완료된 이슈 키 목록 조회 (endDate가 있는 이슈)
     */
    @Query("SELECT i.issueKey FROM JiraIssue i WHERE i.endDate IS NOT NULL")
    Set<String> findCompletedIssueKeys();

    /**
     * 주어진 이슈 키들 중 endDate가 있는 이슈들의 endDate 조회
     * @param issueKeys 조회할 이슈 키 목록
     * @return 이슈 키와 endDate 쌍의 리스트 (Object[0]=issueKey, Object[1]=endDate)
     */
    @Query("SELECT i.issueKey, i.endDate FROM JiraIssue i WHERE i.issueKey IN :issueKeys AND i.endDate IS NOT NULL")
    List<Object[]> findEndDatesByIssueKeys(@Param("issueKeys") Set<String> issueKeys);
}