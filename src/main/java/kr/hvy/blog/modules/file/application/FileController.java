package kr.hvy.blog.modules.file.application;

import io.hypersistence.tsid.TSID;
import kr.hvy.blog.modules.file.application.dto.FileResourceResponse;
import kr.hvy.blog.modules.file.application.service.FilePublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

  private final FilePublicService filePublicService;

  @GetMapping("/{fileId}")
  public ResponseEntity<?> download(@PathVariable String fileId) throws Exception {
    Long id = TSID.from(fileId).toLong();
    FileResourceResponse file = filePublicService.download(id);

    return ResponseEntity.ok()
        .headers(file.getHttpHeaders())
        .body(file.getResource());

  }

}
