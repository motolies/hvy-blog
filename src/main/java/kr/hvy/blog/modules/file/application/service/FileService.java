package kr.hvy.blog.modules.file.application.service;

import java.util.List;
import kr.hvy.blog.modules.file.application.dto.FileCreate;
import kr.hvy.blog.modules.file.application.dto.FileResponse;
import kr.hvy.blog.modules.file.application.specification.FileAuthoritySpecification;
import kr.hvy.blog.modules.file.application.specification.FileCreateSpecification;
import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.file.repository.FileRepository;
import kr.hvy.blog.modules.post.application.service.PostService;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.file.FileStoreUtils;
import kr.hvy.common.specification.Specification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
public class FileService extends AbstractFileManagementService {

  private final PostService postService;


  public FileService(@Value("${path.upload}") String rootLocation, FileRepository fileRepository, PostService postService) {
    super(rootLocation, fileRepository);
    this.postService = postService;
  }


  public FileResponse create(FileCreate fileCreate) {
    Specification.validate(FileCreateSpecification::new, fileCreate);

    try {
      // 파일 먼저 저장
      String relativePath = FileStoreUtils.save(rootLocation.toString(), fileCreate.getFile(), fileCreate.getPostId());
      Resource resource = loadAsResource(relativePath);

      // PostEntity를 통해 File 생성 및 저장 (DDD Aggregate 패턴)
      return postService.addFile(fileCreate.getPostId(), fileCreate, resource, relativePath);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  // 기존 API 호환성을 위한 메서드 (fileId만으로 삭제)
  public DeleteResponse<Long> delete(Long fileId) {
    // 파일 조회 및 권한 체크
    File file = findById(fileId);
    Specification.validate(FileAuthoritySpecification::new, file);

    // 파일이 속한 PostEntity를 찾아서 삭제
    Long postId = file.getPost().getId();
    postService.removeFile(postId, fileId);
    return DeleteResponse.<Long>builder()
        .id(fileId).build();
  }

  public List<FileResponse> getFilesByPostId(Long postId) {
    // 파일 목록 조회 시 권한 체크는 각 게시물에 대해 이미 수행됨
    // 여기서는 게시물에 접근 가능한 경우에만 호출되므로 추가 체크 불필요
    return postService.getFilesByPostId(postId);
  }

  public void deleteFile(String path) {
    FileStoreUtils.deleteFile(rootLocation.toString(), path);
  }

}
