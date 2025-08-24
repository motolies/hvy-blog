package kr.hvy.blog.modules.file.application.service;

import kr.hvy.blog.modules.file.application.dto.FileResourceResponse;
import kr.hvy.blog.modules.file.application.specification.FileAuthoritySpecification;
import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.file.repository.FileRepository;
import kr.hvy.common.core.specification.Specification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
public class FilePublicService extends AbstractFileManagementService {

  public FilePublicService(@Value("${path.upload}") String rootLocation, FileRepository fileRepository) {
    super(rootLocation, fileRepository);
  }

  public FileResourceResponse download(Long id) throws Exception {
    File file = findById(id);
    // 권한 체크
    Specification.validate(FileAuthoritySpecification::new, file);

    return FileResourceResponse.builder()
        .originalName(file.getOriginName())
        .type(file.getType())
        .resource(loadAsResource(file.getPath()))
        .build();
  }
}
