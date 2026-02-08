package kr.hvy.blog.modules.file.application;

import io.hypersistence.tsid.TSID;
import java.util.List;
import kr.hvy.blog.modules.file.application.dto.FileCreate;
import kr.hvy.blog.modules.file.application.dto.FileResponse;
import kr.hvy.blog.modules.file.application.service.FileService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
  public List<FileResponse> list(@PathVariable Long postId) {
    return fileService.getFilesByPostId(postId);
  }

  /**
   * 파일 업로드
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public FileResponse upload(@ModelAttribute FileCreate fileCreate) {
    return fileService.create(fileCreate);
  }

  /**
   * 파일 삭제
   */
  @DeleteMapping("/{fileId}")
  public DeleteResponse<Long> delete(@PathVariable String fileId) {
    Long id = TSID.from(fileId).toLong();
    return fileService.delete(id);
  }

}
