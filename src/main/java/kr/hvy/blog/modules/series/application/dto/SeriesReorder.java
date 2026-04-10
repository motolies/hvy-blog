package kr.hvy.blog.modules.series.application.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** 시리즈 내 포스트 순서 재배치 (postId 목록을 원하는 순서대로 전달) */
@Value
@Builder
@Jacksonized
public class SeriesReorder {

  @NotEmpty(message = "포스트 ID 목록은 필수입니다.")
  List<Long> postIds;
}
