package kr.hvy.blog.modules.series.application.dto;

import java.util.List;
import kr.hvy.common.application.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class SeriesResponse {

  Long id;
  String title;
  String description;
  List<SeriesPostResponse> posts;
  EventLog created;
  EventLog updated;
}
