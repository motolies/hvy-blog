package kr.hvy.blog.modules.file.application.service;

import java.util.List;
import kr.hvy.blog.modules.file.application.port.in.FileManagementUseCase;
import kr.hvy.blog.modules.file.application.port.out.FileManagementPort;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.file.domain.FileMapper;
import kr.hvy.blog.modules.file.domain.FileService;
import kr.hvy.blog.modules.file.domain.dto.FileCreate;
import kr.hvy.blog.modules.file.domain.dto.FileResponse;
import kr.hvy.blog.modules.file.domain.specification.FileCreateSpecification;
import kr.hvy.blog.modules.post.application.port.out.PostManagementPort;
import kr.hvy.blog.modules.post.domain.Post;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.file.FileStoreUtils;
import kr.hvy.common.layer.UseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@Transactional
public class FileManagementService extends AbstractFileManagementService implements FileManagementUseCase {

  private final FileManagementPort fileManagementPort;
  private final FileService fileService;
  private final PostManagementPort postManagementPort;
  private final FileCreateSpecification fileCreateSpecification = new FileCreateSpecification();
  private final FileMapper fileMapper;

  public FileManagementService(@Value("${path.upload}") String rootLocation, FileManagementPort fileManagementPort, FileService fileService, PostManagementPort postManagementPort,
      FileMapper fileMapper) {
    super(rootLocation);
    this.fileManagementPort = fileManagementPort;
    this.fileService = fileService;
    this.postManagementPort = postManagementPort;
    this.fileMapper = fileMapper;
  }


  @Override
  public FileResponse create(FileCreate fileCreate) {
    fileCreateSpecification.validateException(fileCreate);

    try {
      // todo : refactoring 필요
      // 파일 먼저 저장
      String relativePath = FileStoreUtils.save(rootLocation.toString(), fileCreate.getFile(), fileCreate.getPostId());
      Resource resource = loadAsResource(relativePath);

      // 파일 저장 후 파일 정보를 가지고 파일 엔티티 생성
      Post post = postManagementPort.findById(fileCreate.getPostId());

      File file = fileService.create(post, fileCreate, resource, relativePath);
      File savedFile = fileManagementPort.save(file);

      return fileMapper.toResponse(savedFile);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  public DeleteResponse<Long> delete(Long id) {
    fileManagementPort.deleteById(id);
    return DeleteResponse.<Long>builder()
        .id(id).build();
  }

  @Override
  public List<FileResponse> findByPostId(Long postId) {
    return fileManagementPort.findByPostId(postId).stream()
        .map(fileMapper::toResponse)
        .toList();
  }

  public void deleteFile(String path) {
    FileStoreUtils.deleteFile(rootLocation.toString(), path);
  }

}
