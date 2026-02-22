package kr.hvy.blog.modules.file.domain.entity;

import jakarta.persistence.PostRemove;
import kr.hvy.blog.modules.file.domain.event.FileRemovedEvent;
import kr.hvy.common.core.util.ApplicationContextUtils;

public class FileEntityListener {

  @PostRemove
  public void postRemove(File fileEntity) {
    ApplicationContextUtils.getEventPublisher().publishEvent(new FileRemovedEvent(fileEntity.getPath()));
  }
}
