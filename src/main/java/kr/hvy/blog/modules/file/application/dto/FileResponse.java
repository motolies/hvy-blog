package kr.hvy.blog.modules.file.application.dto;

import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class FileResponse {

  String id;
  String originName;
  String type;
  Long fileSize;

  @Builder.Default
  EventLog created = EventLog.defaultValues();

  public String getResourceUri() {
    return "/api/file/" + this.id;
  }
}
