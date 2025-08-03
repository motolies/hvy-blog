package kr.hvy.blog.modules.category.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class CategoryCreate {

  @JsonIgnore
  String id;

  @NotNull(message = "카테고리 이름은 필수 입니다. ")
  String name;

  @NotNull(message = "부모 카테고리 아이디는 필수 입니다. ")
  String parentId;

  public String getFullName() {
    return "/TEMP/";
  }

  public String getFullPath() {
    return "/TEMP/";
  }

}
