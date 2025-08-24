package kr.hvy.blog.modules.category.domain.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import kr.hvy.blog.modules.category.application.service.CategoryProcedureService;
import kr.hvy.common.core.util.ApplicationContextUtils;

public class CategoryEntityListener {

  @PostPersist
  @PostUpdate
  public void postPersistAndUpdate(Category categoryEntity) {
    CategoryProcedureService procedureService = ApplicationContextUtils.getBean(CategoryProcedureService.class)
        .orElseThrow(() -> new RuntimeException("CategoryProcedureService is not found"));

    procedureService.updateFullName();
  }
}
