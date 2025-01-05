package kr.hvy.blog.modules.file.domain;

import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.common.domain.vo.EventLog;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.springframework.web.multipart.MultipartFile;

@Value
@Builder
@With
public class File {

  Long id;
  String hexId;
  String originName;
  String type;
  String path;
  Long fileSize;
  boolean deleted;
  Post post;
  @Builder.Default
  EventLog created = EventLog.defaultValues();
  MultipartFile file;
}
