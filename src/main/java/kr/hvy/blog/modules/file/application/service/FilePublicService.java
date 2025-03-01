package kr.hvy.blog.modules.file.application.service;

import kr.hvy.blog.modules.file.application.port.in.FilePublicUseCase;
import kr.hvy.blog.modules.file.application.port.out.FileManagementPort;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.file.domain.dto.FileResourceResponse;
import kr.hvy.common.layer.UseCase;
import org.springframework.beans.factory.annotation.Value;

@UseCase

public class FilePublicService extends AbstractFileManagementService implements FilePublicUseCase {

  private final FileManagementPort fileManagementPort;

  public FilePublicService(@Value("${path.upload}") String rootLocation, FileManagementPort fileManagementPort) {
    super(rootLocation);
    this.fileManagementPort = fileManagementPort;
  }


  @Override
  public FileResourceResponse download(Long id) throws Exception {
    File file = fileManagementPort.findById(id);
    return FileResourceResponse.builder()
        .originalName(file.getOriginName())
        .type(file.getType())
        .resource(loadAsResource(file.getPath()))
        .build();
  }

}
