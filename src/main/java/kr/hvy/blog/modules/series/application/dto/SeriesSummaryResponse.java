package kr.hvy.blog.modules.series.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class SeriesSummaryResponse {

  Long id;
  String title;
  String description;
  int postCount;
}
