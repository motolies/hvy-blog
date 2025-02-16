package kr.hvy.blog.modules.category.adapter.out.persistence;

import kr.hvy.blog.modules.category.adapter.out.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaCategoryRepository extends JpaRepository<CategoryEntity, String> {


}
