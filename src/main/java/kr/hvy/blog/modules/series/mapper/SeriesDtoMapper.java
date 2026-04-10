package kr.hvy.blog.modules.series.mapper;

import java.util.List;
import kr.hvy.blog.modules.series.application.dto.SeriesCreate;
import kr.hvy.blog.modules.series.application.dto.SeriesPostResponse;
import kr.hvy.blog.modules.series.application.dto.SeriesResponse;
import kr.hvy.blog.modules.series.application.dto.SeriesSummaryResponse;
import kr.hvy.blog.modules.series.domain.entity.Series;
import kr.hvy.blog.modules.series.domain.entity.SeriesPost;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SeriesDtoMapper {

  Series toDomain(SeriesCreate seriesCreate);

  @Mapping(source = "seriesPosts", target = "posts")
  SeriesResponse toResponse(Series series);

  @Mapping(source = "post.id", target = "postId")
  @Mapping(source = "post.subject", target = "subject")
  SeriesPostResponse toPostResponse(SeriesPost seriesPost);

  List<SeriesPostResponse> toPostResponseList(List<SeriesPost> seriesPosts);

  @Mapping(expression = "java(series.getSeriesPosts().size())", target = "postCount")
  SeriesSummaryResponse toSummaryResponse(Series series);
}
