package kr.hvy.blog.modules.jira.application;

import kr.hvy.blog.modules.jira.application.service.JiraSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/jira/admin")
@RequiredArgsConstructor
public class JiraSyncAdminController {

  private final JiraSyncService jiraSyncService;

  /**
   * 지라 이슈 동기화
   */
  @PostMapping
  public ResponseEntity<?> sync() {
    jiraSyncService.syncAllIssuesAndWorklogs();
    return ResponseEntity
        .ok().build();
  }
}
