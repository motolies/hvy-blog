package kr.hvy.blog.modules.stats.application.dto;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class DailyViewCount {

  LocalDate date;
  long count;
}
