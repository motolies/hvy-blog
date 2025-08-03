package kr.hvy.blog.modules.category.application.service;

import kr.hvy.blog.modules.category.repository.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CategoryProcedureService {

  private final CategoryMapper categoryMapper;

  public void updateFullName() {
    categoryMapper.updateFullName();
  }
}
