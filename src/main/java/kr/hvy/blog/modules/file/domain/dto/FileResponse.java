package kr.hvy.blog.modules.file.domain.dto;

import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileResponse {

  String id;
  String originName;
  String type;
  Long fileSize;
  boolean isDelete;
  @Builder.Default
  EventLog created = EventLog.defaultValues();
}
