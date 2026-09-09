package kr.hvy.blog.modules.jira.application;

import kr.hvy.blog.modules.jira.application.service.JiraSyncAsyncLauncher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/jira/admin")
@RequiredArgsConstructor
public class JiraSyncAdminController {

  private final JiraSyncAsyncLauncher jiraSyncAsyncLauncher;

  /**
   * 지라 이슈 동기화. 동기화는 수 분이 걸리므로 비동기 진입점으로 던지고 즉시 응답한다.
   */
  @PostMapping
  public void sync() {
    jiraSyncAsyncLauncher.syncAllIssuesAndWorklogsAsync();
  }
}
