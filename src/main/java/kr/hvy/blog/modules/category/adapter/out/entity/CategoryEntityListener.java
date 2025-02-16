package kr.hvy.blog.modules.category.adapter.out.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import kr.hvy.common.util.SpringContextUtils;

public class CategoryEntityListener {

  @PostPersist
  @PostUpdate
  public void postPersistAndUpdate(CategoryEntity categoryEntity) {
    CategoryProcedureService procedureService = SpringContextUtils.getBean(CategoryProcedureService.class)
        .orElseThrow(() -> new RuntimeException("CategoryProcedureService is not found"));

    procedureService.updateFullName();
  }
}
