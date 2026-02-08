package kr.hvy.blog.modules.file.domain.entity;

import jakarta.persistence.PostRemove;
import kr.hvy.blog.modules.file.domain.event.FileRemovedEvent;
import kr.hvy.common.core.util.ApplicationContextUtils;
import org.springframework.context.ApplicationEventPublisher;

public class FileEntityListener {

  @PostRemove
  public void postRemove(File fileEntity) {
    ApplicationEventPublisher eventPublisher = ApplicationContextUtils.getBean(ApplicationEventPublisher.class)
        .orElseThrow(() -> new RuntimeException("ApplicationEventPublisher is not found"));

    eventPublisher.publishEvent(new FileRemovedEvent(fileEntity.getPath()));
  }
}
