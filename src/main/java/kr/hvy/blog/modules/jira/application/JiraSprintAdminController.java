package kr.hvy.blog.modules.jira.application;

import kr.hvy.blog.modules.jira.application.dto.JiraSprintSummaryResponseDto;
import kr.hvy.blog.modules.jira.application.service.JiraQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
  @GetMapping("/{year}")
  public ResponseEntity<JiraSprintSummaryResponseDto> sprintSummary(@PathVariable String year) {
    log.info("스프린트 서머리 조회 요청: year={}", year);
    
    try {
      JiraSprintSummaryResponseDto response = jiraQueryService.getSprintSummary(year);
      
      log.info("스프린트 서머리 조회 완료: year={}, 작업자 수={}, 총 스프린트 수={}", 
          year, response.getAssigneeSummaries().size(), response.getSprints().size());
      
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("스프린트 서머리 조회 중 오류 발생: year={}", year, e);
      throw e;
    }
  }
}
