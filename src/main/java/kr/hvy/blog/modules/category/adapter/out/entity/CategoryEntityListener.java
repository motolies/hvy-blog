package kr.hvy.blog.modules.category.adapter.out.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import kr.hvy.common.util.ApplicationContextUtils;

public class CategoryEntityListener {

  @PostPersist
  @PostUpdate
  public void postPersistAndUpdate(CategoryEntity categoryEntity) {
    CategoryProcedureService procedureService = ApplicationContextUtils.getBean(CategoryProcedureService.class)
        .orElseThrow(() -> new RuntimeException("CategoryProcedureService is not found"));

    procedureService.updateFullName();
  }
}
