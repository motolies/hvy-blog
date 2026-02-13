package kr.hvy.blog.modules.memo.application.dto;

import kr.hvy.common.application.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class MemoResponse {

  String id;
  String content;
  MemoCategoryResponse category;
  boolean deleted;
  EventLog created;
  EventLog updated;
}
