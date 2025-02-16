package kr.hvy.blog.modules.category.adapter.out.persistence.mapper;

import java.util.List;
import kr.hvy.blog.modules.category.domain.dto.CategoryFlatResponse;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryRDBMapper {

  List<CategoryFlatResponse> findAllCategory();

  void updateFullName();
}
