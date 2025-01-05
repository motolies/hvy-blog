package kr.hvy.blog.modules.file.application.port.out;

import java.util.List;
import kr.hvy.blog.modules.file.domain.File;

public interface FileManagementPort {

  File save(File file);

  File findById(Long id);

  void deleteById(Long id);

  List<File> findByPostId(Long postId);
}
