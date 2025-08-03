package kr.hvy.blog.modules.file.application.specification;

import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.file.application.dto.FileCreate;
import kr.hvy.common.specification.Specification;
import org.apache.commons.collections4.CollectionUtils;

public class FileCreateSpecification implements Specification<FileCreate> {

  List<String> errorMessages = new ArrayList<>();

  @Override
  public boolean isSatisfiedBy(FileCreate fileCreate) {
    errorMessages.clear();

    if (fileCreate.getPostId() == null || fileCreate.getPostId() < 1) {
      errorMessages.add("저장할 포스트 Id가 없습니다.");
    }

    if (fileCreate.getFile().isEmpty()) {
      errorMessages.add("저장할 파일이 없습니다.");
    }

    return !CollectionUtils.isNotEmpty(errorMessages);
  }

  @Override
  public String getErrorMessage() {
    return String.join(", ", errorMessages);
  }
}
