package kr.hvy.blog.modules.tag.domain;

import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class Tag {

  Long id;
  String name;
  int postCount;
  @Builder.Default
  EventLog created = EventLog.defaultValues();

}
