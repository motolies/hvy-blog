package kr.hvy.blog.modules.category.domain.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import kr.hvy.blog.modules.category.domain.event.CategoryChangedEvent;
import kr.hvy.common.core.util.ApplicationContextUtils;
import org.springframework.context.ApplicationEventPublisher;

public class CategoryEntityListener {

  @PostPersist
  @PostUpdate
  public void postPersistAndUpdate(Category categoryEntity) {
    ApplicationEventPublisher eventPublisher = ApplicationContextUtils.getBean(ApplicationEventPublisher.class)
        .orElseThrow(() -> new RuntimeException("ApplicationEventPublisher is not found"));

    eventPublisher.publishEvent(new CategoryChangedEvent(categoryEntity.getId()));
  }
}
