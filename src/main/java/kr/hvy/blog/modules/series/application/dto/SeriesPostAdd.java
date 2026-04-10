package kr.hvy.blog.modules.series.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class SeriesPostAdd {

  @NotNull(message = "포스트 ID는 필수입니다.")
  Long postId;
}
