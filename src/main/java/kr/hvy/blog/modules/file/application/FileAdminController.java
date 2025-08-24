package kr.hvy.blog.modules.file.application;

import io.hypersistence.tsid.TSID;
import kr.hvy.blog.modules.file.application.dto.FileCreate;
import kr.hvy.blog.modules.file.application.service.FileService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/file/admin")
@RequiredArgsConstructor
public class FileAdminController {

  private final FileService fileService;

  /**
   * 파일 목록 조회
   */
  @GetMapping("/list/{postId}")
  public ResponseEntity<?> list(@PathVariable Long postId) {
    return ResponseEntity
        .ok()
        .body(fileService.getFilesByPostId(postId));
  }

  /**
   * 파일 업로드
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> upload(@ModelAttribute FileCreate fileCreate) {
    return ResponseEntity
        .ok()
        .body(fileService.create(fileCreate));
  }

  /**
   * 파일 삭제
   */
  @DeleteMapping("/{fileId}")
  public ResponseEntity<?> delete(@PathVariable String fileId) {
    Long id = TSID.from(fileId).toLong();
    fileService.delete(id);
    return ResponseEntity
        .ok()
        .body(DeleteResponse.<String>builder()
            .id(fileId)
            .build());
  }

}
