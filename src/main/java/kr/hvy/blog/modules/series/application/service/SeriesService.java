package kr.hvy.blog.modules.series.application.service;

import java.util.List;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.blog.modules.post.repository.PostRepository;
import kr.hvy.blog.modules.series.application.dto.SeriesCreate;
import kr.hvy.blog.modules.series.application.dto.SeriesPostAdd;
import kr.hvy.blog.modules.series.application.dto.SeriesReorder;
import kr.hvy.blog.modules.series.application.dto.SeriesResponse;
import kr.hvy.blog.modules.series.application.dto.SeriesSummaryResponse;
import kr.hvy.blog.modules.series.application.dto.SeriesUpdate;
import kr.hvy.blog.modules.series.domain.entity.Series;
import kr.hvy.blog.modules.series.domain.entity.SeriesPost;
import kr.hvy.blog.modules.series.mapper.SeriesDtoMapper;
import kr.hvy.blog.modules.series.repository.SeriesPostRepository;
import kr.hvy.blog.modules.series.repository.SeriesRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SeriesService {

  private final SeriesRepository seriesRepository;
  private final SeriesPostRepository seriesPostRepository;
  private final PostRepository postRepository;
  private final SeriesDtoMapper seriesDtoMapper;

  private Series findById(Long id) {
    return seriesRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("Not Found Series."));
  }

  /** 시리즈 전체 목록 (요약) */
  @Transactional(readOnly = true)
  public List<SeriesSummaryResponse> getAllSeries() {
    return seriesRepository.findAll().stream()
        .map(seriesDtoMapper::toSummaryResponse)
        .toList();
  }

  /** 시리즈 상세 조회 (포스트 목록 포함) */
  @Transactional(readOnly = true)
  public SeriesResponse getSeriesDetail(Long id) {
    Series series = findById(id);
    return seriesDtoMapper.toResponse(series);
  }

  /** 특정 포스트가 속한 시리즈 정보 조회 */
  @Transactional(readOnly = true)
  public SeriesResponse getSeriesByPostId(Long postId) {
    List<SeriesPost> seriesPosts = seriesPostRepository.findByPostId(postId);
    if (seriesPosts.isEmpty()) {
      return null;
    }
    return seriesDtoMapper.toResponse(seriesPosts.getFirst().getSeries());
  }

  /** 시리즈 생성 */
  public SeriesResponse create(SeriesCreate createDto) {
    Series series = seriesDtoMapper.toDomain(createDto);
    Series saved = seriesRepository.save(series);
    return seriesDtoMapper.toResponse(saved);
  }

  /** 시리즈 수정 */
  public SeriesResponse update(Long id, SeriesUpdate updateDto) {
    Series series = findById(id);
    series.update(updateDto.getTitle(), updateDto.getDescription());
    return seriesDtoMapper.toResponse(series);
  }

  /** 시리즈 삭제 */
  public DeleteResponse<Long> delete(Long id) {
    seriesRepository.deleteById(id);
    return DeleteResponse.<Long>builder().id(id).build();
  }

  /** 시리즈에 포스트 추가 (맨 뒤에 추가) */
  public SeriesResponse addPost(Long seriesId, SeriesPostAdd addDto) {
    Series series = findById(seriesId);
    Post post = postRepository.findById(addDto.getPostId())
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));

    int nextSeq = series.getSeriesPosts().stream()
        .mapToInt(SeriesPost::getSeq)
        .max()
        .orElse(0) + 1;

    SeriesPost seriesPost = SeriesPost.builder()
        .series(series)
        .post(post)
        .seq(nextSeq)
        .build();
    series.getSeriesPosts().add(seriesPost);
    seriesRepository.flush();

    return seriesDtoMapper.toResponse(series);
  }

  /** 시리즈에서 포스트 제거 */
  public SeriesResponse removePost(Long seriesId, Long postId) {
    Series series = findById(seriesId);
    SeriesPost seriesPost = seriesPostRepository.findBySeriesIdAndPostId(seriesId, postId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Series Post."));
    series.getSeriesPosts().remove(seriesPost);
    seriesRepository.flush();

    return seriesDtoMapper.toResponse(series);
  }

  /** 시리즈 내 포스트 순서 재배치 */
  public SeriesResponse reorderPosts(Long seriesId, SeriesReorder reorderDto) {
    Series series = findById(seriesId);
    List<SeriesPost> seriesPosts = series.getSeriesPosts();

    for (int i = 0; i < reorderDto.getPostIds().size(); i++) {
      Long postId = reorderDto.getPostIds().get(i);
      seriesPosts.stream()
          .filter(sp -> sp.getPost().getId().equals(postId))
          .findFirst()
          .ifPresent(sp -> sp.setSeq(reorderDto.getPostIds().indexOf(postId) + 1));
    }
    seriesRepository.flush();

    return seriesDtoMapper.toResponse(findById(seriesId));
  }
}
