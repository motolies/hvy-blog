package kr.hvy.blog.modules.category.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.category.application.dto.CategoryFlatResponse;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper {

  List<CategoryFlatResponse> findAllCategory();

  void updateFullName();
}
