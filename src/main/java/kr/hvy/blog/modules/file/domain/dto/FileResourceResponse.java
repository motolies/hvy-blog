package kr.hvy.blog.modules.file.domain.dto;

import java.nio.charset.StandardCharsets;
import kr.hvy.common.file.MediaUtils;
import lombok.Builder;
import lombok.Getter;
import lombok.With;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@Getter
@Builder
@With
public class FileResourceResponse {

  String originalName;
  String type;
  Resource resource;

  public HttpHeaders getHttpHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + new String(originalName.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + "\"");
    if (MediaUtils.containsImageMediaType(this.type)) {
      headers.setContentType(MediaType.valueOf(this.type));
    } else {
      headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    }
    return headers;
  }
}
