package kr.hvy.blog.modules.series.application;

import java.util.List;
import kr.hvy.blog.modules.series.application.dto.SeriesResponse;
import kr.hvy.blog.modules.series.application.dto.SeriesSummaryResponse;
import kr.hvy.blog.modules.series.application.service.SeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/series")
@RequiredArgsConstructor
public class SeriesController {

  private final SeriesService seriesService;

  /** 시리즈 전체 목록 */
  @GetMapping
  public List<SeriesSummaryResponse> getAllSeries() {
    return seriesService.getAllSeries();
  }

  /** 시리즈 상세 (포스트 목록 포함) */
  @GetMapping("/{seriesId}")
  public SeriesResponse getSeriesDetail(@PathVariable Long seriesId) {
    return seriesService.getSeriesDetail(seriesId);
  }

  /** 특정 포스트가 속한 시리즈 조회 */
  @GetMapping("/by-post/{postId}")
  public SeriesResponse getSeriesByPostId(@PathVariable Long postId) {
    return seriesService.getSeriesByPostId(postId);
  }
}
