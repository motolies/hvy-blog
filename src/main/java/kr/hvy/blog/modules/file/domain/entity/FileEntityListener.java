package kr.hvy.blog.modules.file.domain.entity;

import jakarta.persistence.PostRemove;
import kr.hvy.blog.modules.file.application.service.FileService;
import kr.hvy.common.util.ApplicationContextUtils;

public class FileEntityListener {

  @PostRemove
  public void postPersistAndUpdate(File fileEntity) {
    FileService fileService = ApplicationContextUtils.getBean(FileService.class)
        .orElseThrow(() -> new RuntimeException("FileManagementService is not found"));

    fileService.deleteFile(fileEntity.getPath());
  }
}
