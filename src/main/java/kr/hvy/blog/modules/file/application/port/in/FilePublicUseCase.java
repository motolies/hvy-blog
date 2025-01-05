package kr.hvy.blog.modules.file.application.port.in;

import kr.hvy.blog.modules.file.domain.dto.FileResourceResponse;
import org.springframework.core.io.Resource;

public interface FilePublicUseCase {
  FileResourceResponse download(Long id) throws Exception;
}
