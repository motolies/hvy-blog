package kr.hvy.blog.modules.jira.application;

import kr.hvy.blog.modules.jira.application.dto.JiraSprintSummaryResponse;
import kr.hvy.blog.modules.jira.application.dto.SprintWorkerSummaryResponse;
import kr.hvy.blog.modules.jira.application.dto.WorklogDetailResponse;
import kr.hvy.blog.modules.jira.application.service.JiraQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
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
  public ResponseEntity<JiraSprintSummaryResponse> sprintSummary(@PathVariable String year) {
    log.info("스프린트 서머리 조회 요청: year={}", year);

    try {
      JiraSprintSummaryResponse response = jiraQueryService.getSprintSummary(year);

      log.info("스프린트 서머리 조회 완료: year={}, 작업자 수={}, 총 스프린트 수={}",
          year, response.getAssigneeSummaries().size(), response.getSprints().size());

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("스프린트 서머리 조회 중 오류 발생: year={}", year, e);
      throw e;
    }
  }


  /**
   * 스프린트 별 작업자 서머리
   *
   * @param sprint the sprint
   * @param worker the worker (optional)
   * @return the response entity
   */
  @GetMapping("/{sprint}")
  public ResponseEntity<List<SprintWorkerSummaryResponse>> sprintWorkerSummary(
      @PathVariable String sprint,
      @RequestParam(required = false) String worker) {

    log.info("스프린트-작업자 서머리 조회 요청: sprint={}, worker={}", sprint, worker);

    try {
      List<SprintWorkerSummaryResponse> response = jiraQueryService.getSprintWorkerSummary(sprint, worker);

      log.info("스프린트-작업자 서머리 조회 완료: sprint={}, worker={}, 결과 수={}",
          sprint, worker, response.size());

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("스프린트-작업자 서머리 조회 중 오류 발생: sprint={}, worker={}", sprint, worker, e);
      throw e;
    }
  }


  /**
   * 작업로그 상세
   *
   * @param issueKey the issue key
   * @return the response entity
   */
  @GetMapping("/issue/{issueKey}")
  public ResponseEntity<List<WorklogDetailResponse>> workHistorySummary(@PathVariable String issueKey) {

    log.info("작업로그 상세 조회 요청: issueKey={}", issueKey);

    try {
      List<WorklogDetailResponse> response = jiraQueryService.getWorklogDetails(issueKey);

      log.info("작업로그 상세 조회 완료: issueKey={}, 작업로그 수={}",
          issueKey, response.size());

      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("작업로그 상세 조회 중 오류 발생: issueKey={}", issueKey, e);
      throw e;
    }
  }

}
