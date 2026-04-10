package kr.hvy.blog.modules.series.repository;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.series.domain.entity.SeriesPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeriesPostRepository extends JpaRepository<SeriesPost, Long> {

  List<SeriesPost> findBySeriesIdOrderBySeqAsc(Long seriesId);

  Optional<SeriesPost> findBySeriesIdAndPostId(Long seriesId, Long postId);

  List<SeriesPost> findByPostId(Long postId);

}
