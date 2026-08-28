package kr.hvy.blog.modules.stats.application;

import kr.hvy.blog.modules.stats.application.dto.HealthStats;
import kr.hvy.blog.modules.stats.application.dto.PipelineStats;
import kr.hvy.blog.modules.stats.application.dto.StatsSummary;
import kr.hvy.blog.modules.stats.application.dto.TrafficStats;
import kr.hvy.blog.modules.stats.application.service.StatsHealthService;
import kr.hvy.blog.modules.stats.application.service.StatsPipelineService;
import kr.hvy.blog.modules.stats.application.service.StatsSummaryService;
import kr.hvy.blog.modules.stats.application.service.StatsTrafficService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 대시보드 집계 API.
 * <p>
 * <b>왜 하나가 아니라 넷인가.</b>
 * <ul>
 *   <li>갱신 주기가 다르다 — 콘텐츠는 5분, 이상 징후는 60초면 충분하다.
 *       한 덩어리면 가장 짧은 주기에 맞춰 전부를 다시 계산해야 한다.</li>
 *   <li>비용이 다르다 — 24시간 p95 집계가 가장 비싼데, {@code COUNT(*) FROM tb_tag} 가
 *       그 비용을 같이 낼 이유가 없다.</li>
 *   <li>독립 실패 — 로그 집계가 느려져도 콘텐츠 카드는 떠야 한다.
 *       프론트의 위젯별 재시도 버튼도 이 경계를 따른다.</li>
 * </ul>
 * 과분할은 피했다. Next BFF 를 경유하므로 왕복이 곱절이 된다 — 섹션당 하나, 총 넷.
 * <p>
 * 경로가 {@code /api/*&#47;admin/**} 에 걸리므로 SecurityConfig 의 ROLE_ADMIN 검사가 자동 적용된다.
 */
@RestController
@RequestMapping("/api/stats/admin")
@RequiredArgsConstructor
public class AdminStatsController {

  private final StatsSummaryService statsSummaryService;
  private final StatsTrafficService statsTrafficService;
  private final StatsHealthService statsHealthService;
  private final StatsPipelineService statsPipelineService;

  /** 콘텐츠 현황 — 글·카테고리·태그·첨부. 로그 테이블을 건드리지 않는다. */
  @GetMapping("/summary")
  public StatsSummary getSummary(@RequestParam(defaultValue = "12") int months) {
    return statsSummaryService.getSummary(months);
  }

  /** 트래픽 — 일별 추이·방문자·인기 글·인기 경로. */
  @GetMapping("/traffic")
  public TrafficStats getTraffic(@RequestParam(defaultValue = "30") int days) {
    return statsTrafficService.getTraffic(days);
  }

  /** 이상 징후 — 에러·응답시간·스케줄러·외부 API. */
  @GetMapping("/health")
  public HealthStats getHealth(@RequestParam(defaultValue = "24") int hours) {
    return statsHealthService.getHealth(hours);
  }

  /** 사이드 파이프라인 — 핫딜 수집·메모·Jira. */
  @GetMapping("/pipeline")
  public PipelineStats getPipeline(@RequestParam(defaultValue = "24") int hours) {
    return statsPipelineService.getPipeline(hours);
  }
}
