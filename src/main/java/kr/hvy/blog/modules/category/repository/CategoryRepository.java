package kr.hvy.blog.modules.category.repository;

import kr.hvy.blog.modules.category.domain.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, String> {


}
