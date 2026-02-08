package kr.hvy.blog.modules.jira.application;

import kr.hvy.blog.modules.jira.application.dto.JiraSprintSummaryResponse;
import kr.hvy.blog.modules.jira.application.dto.SprintWorkerSummaryResponse;
import kr.hvy.blog.modules.jira.application.dto.WorklogDetailResponse;
import kr.hvy.blog.modules.jira.application.service.JiraQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/jira/admin/sprint")
@RequiredArgsConstructor
public class JiraSprintAdminController {

  private final JiraQueryService jiraQueryService;

  /**
   * 특정 연도의 스프린트별 작업자별 스토리포인트 서머리를 조회합니다.
   *
   * @param year 조회할 연도 (예: 2024)
   * @return 스프린트 서머리 응답
   */
  @GetMapping("/summary/{year}")
  public JiraSprintSummaryResponse sprintSummary(@PathVariable String year) {
    return jiraQueryService.getSprintSummary(year);
  }

  /**
   * 스프린트 별 작업자 서머리
   *
   * @param sprint the sprint
   * @param worker the worker (optional)
   * @return 작업자 서머리 목록
   */
  @GetMapping("/{sprint}")
  public List<SprintWorkerSummaryResponse> sprintWorkerSummary(
      @PathVariable String sprint,
      @RequestParam(required = false) String worker) {
    return jiraQueryService.getSprintWorkerSummary(sprint, worker);
  }

  /**
   * 작업로그 상세
   *
   * @param issueKey the issue key
   * @return 작업로그 상세 목록
   */
  @GetMapping("/issue/{issueKey}")
  public List<WorklogDetailResponse> workHistorySummary(@PathVariable String issueKey) {
    return jiraQueryService.getWorklogDetails(issueKey);
  }

}
