package kr.hvy.blog.modules.file.adapter.in;

import io.hypersistence.tsid.TSID;
import kr.hvy.blog.modules.file.application.port.in.FilePublicUseCase;
import kr.hvy.blog.modules.file.domain.dto.FileResourceResponse;
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

  private final FilePublicUseCase filePublicUseCase;

  @GetMapping("/{fileId}")
  public ResponseEntity<?> download(@PathVariable String fileId) throws Exception {
    Long id = TSID.from(fileId).toLong();
    FileResourceResponse file = filePublicUseCase.download(id);

    return ResponseEntity.ok()
        .headers(file.getHttpHeaders())
        .body(file.getResource());

  }

}
