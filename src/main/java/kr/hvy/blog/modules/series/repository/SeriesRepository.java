package kr.hvy.blog.modules.series.repository;

import kr.hvy.blog.modules.series.domain.entity.Series;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeriesRepository extends JpaRepository<Series, Long> {

}
