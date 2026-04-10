package kr.hvy.blog.modules.claude.application;

import kr.hvy.blog.modules.claude.application.service.ClaudeCodeRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claude")
@RequiredArgsConstructor
public class ClaudeController {

  private final ClaudeCodeRefreshService claudeCodeRefreshService;

  @PostMapping("/refresh")
  public void refresh() {
    claudeCodeRefreshService.refreshAndPing();
  }
}
