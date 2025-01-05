package kr.hvy.blog.modules.file.application.port.in;

import java.util.List;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.file.domain.dto.FileCreate;
import kr.hvy.blog.modules.file.domain.dto.FileResponse;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface FileManagementUseCase extends CrudUseCase<File, FileResponse, FileCreate, Void, Long> {

  FileResponse create(FileCreate fileCreate);

  Long delete(Long id);

  List<FileResponse> findByPostId(Long id);

}
