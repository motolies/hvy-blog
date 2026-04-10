package kr.hvy.blog.modules.stats.application;

import kr.hvy.blog.modules.stats.application.dto.StatsOverview;
import kr.hvy.blog.modules.stats.application.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats/admin")
@RequiredArgsConstructor
public class AdminStatsController {

  private final StatsService statsService;

  @GetMapping("/overview")
  public StatsOverview getOverview(
      @RequestParam(defaultValue = "30") int days) {
    return statsService.getOverview(days);
  }
}
