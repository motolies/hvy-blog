package kr.hvy.blog.modules.file.adapter.out.entity;

import jakarta.persistence.PreRemove;
import kr.hvy.blog.modules.file.application.service.FileManagementService;
import kr.hvy.common.util.ApplicationContextUtils;

public class FileEntityListener {

  @PreRemove
  public void postPersistAndUpdate(FileEntity fileEntity) {
    FileManagementService fileManagementService = ApplicationContextUtils.getBean(FileManagementService.class)
        .orElseThrow(() -> new RuntimeException("FileManagementService is not found"));

    fileManagementService.deleteFile(fileEntity.getPath());
  }
}
