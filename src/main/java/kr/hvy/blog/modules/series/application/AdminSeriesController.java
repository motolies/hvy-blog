package kr.hvy.blog.modules.series.application;

import jakarta.validation.Valid;
import kr.hvy.blog.modules.series.application.dto.SeriesCreate;
import kr.hvy.blog.modules.series.application.dto.SeriesPostAdd;
import kr.hvy.blog.modules.series.application.dto.SeriesReorder;
import kr.hvy.blog.modules.series.application.dto.SeriesResponse;
import kr.hvy.blog.modules.series.application.dto.SeriesUpdate;
import kr.hvy.blog.modules.series.application.service.SeriesService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/series/admin")
@RequiredArgsConstructor
public class AdminSeriesController {

  private final SeriesService seriesService;

  /** 시리즈 생성 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SeriesResponse create(@RequestBody @Valid SeriesCreate seriesCreate) {
    return seriesService.create(seriesCreate);
  }

  /** 시리즈 수정 */
  @PutMapping("/{seriesId}")
  public SeriesResponse update(@PathVariable Long seriesId, @RequestBody @Valid SeriesUpdate seriesUpdate) {
    return seriesService.update(seriesId, seriesUpdate);
  }

  /** 시리즈 삭제 */
  @DeleteMapping("/{seriesId}")
  public DeleteResponse<Long> delete(@PathVariable Long seriesId) {
    return seriesService.delete(seriesId);
  }

  /** 시리즈에 포스트 추가 */
  @PostMapping("/{seriesId}/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public SeriesResponse addPost(@PathVariable Long seriesId, @RequestBody @Valid SeriesPostAdd seriesPostAdd) {
    return seriesService.addPost(seriesId, seriesPostAdd);
  }

  /** 시리즈에서 포스트 제거 */
  @DeleteMapping("/{seriesId}/posts/{postId}")
  public SeriesResponse removePost(@PathVariable Long seriesId, @PathVariable Long postId) {
    return seriesService.removePost(seriesId, postId);
  }

  /** 시리즈 포스트 순서 재배치 */
  @PutMapping("/{seriesId}/posts/reorder")
  public SeriesResponse reorderPosts(@PathVariable Long seriesId, @RequestBody @Valid SeriesReorder seriesReorder) {
    return seriesService.reorderPosts(seriesId, seriesReorder);
  }
}
