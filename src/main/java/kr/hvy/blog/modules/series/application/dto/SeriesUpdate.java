package kr.hvy.blog.modules.series.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class SeriesUpdate {

  @NotBlank(message = "시리즈 제목은 필수입니다.")
  String title;

  String description;
}
