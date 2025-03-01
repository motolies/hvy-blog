package kr.hvy.blog.modules.file.application.port.in;

import java.util.List;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.file.domain.dto.FileCreate;
import kr.hvy.blog.modules.file.domain.dto.FileResponse;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface FileManagementUseCase extends CrudUseCase<File, FileResponse, FileCreate, Void, Long, DeleteResponse<Long>> {

  List<FileResponse> findByPostId(Long id);

}
