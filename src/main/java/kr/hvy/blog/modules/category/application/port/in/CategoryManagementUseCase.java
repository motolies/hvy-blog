package kr.hvy.blog.modules.category.application.port.in;

import kr.hvy.blog.modules.category.domain.Category;
import kr.hvy.blog.modules.category.domain.dto.CategoryCreate;
import kr.hvy.blog.modules.category.domain.dto.CategoryResponse;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface CategoryManagementUseCase extends CrudUseCase<Category, CategoryResponse, CategoryCreate, Void, String> {

}
