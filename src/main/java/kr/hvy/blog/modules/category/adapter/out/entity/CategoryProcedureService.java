package kr.hvy.blog.modules.category.adapter.out.entity;

import kr.hvy.blog.modules.category.adapter.out.persistence.mapper.CategoryRDBMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CategoryProcedureService {

  private final CategoryRDBMapper categoryRDBMapper;

  public void updateFullName() {
    categoryRDBMapper.updateFullName();
  }
}
