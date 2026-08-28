package kr.hvy.blog.modules.stats.repository.mapper;

import java.time.Instant;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.CategoryDistribution;
import kr.hvy.blog.modules.stats.application.dto.MonthlyPostCount;
import kr.hvy.blog.modules.stats.application.dto.PopularPost;
import kr.hvy.blog.modules.stats.application.dto.PostSummary;
import kr.hvy.blog.modules.stats.application.dto.TagDistribution;
import kr.hvy.blog.modules.stats.application.dto.TaxonomySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 콘텐츠 집계 — tb_post / tb_category / tb_tag / tb_file / tb_post_draft.
 * <p>
 * 로그 테이블은 건드리지 않는다(→ StatsLogMapper). 갱신 주기와 비용 프로파일이 다르기 때문에
 * 엔드포인트를 나눴고, 매퍼도 같은 경계로 나눈다.
 */
@Mapper
public interface StatsMapper {

  PostSummary findPostSummary();

  TaxonomySummary findTaxonomySummary();

  List<CategoryDistribution> findCategoryDistribution();

  List<TagDistribution> findTagDistribution(@Param("limit") int limit);

  /** 누적 view_count 기준 인기 글. 조회수 beacon 배포 이후에야 의미 있는 값이 된다. */
  List<PopularPost> findPopularPosts(@Param("limit") int limit);

  /** ⚠️ published_at 이 없어 작성일(created_at) 기준이다. 화면 라벨도 그렇게 표기할 것. */
  List<MonthlyPostCount> findMonthlyPostCounts(@Param("from") Instant from,
      @Param("zoneId") String zoneId);
}
