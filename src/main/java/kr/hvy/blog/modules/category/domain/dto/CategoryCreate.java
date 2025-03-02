package kr.hvy.blog.modules.category.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCreate {

  @JsonIgnore
  private String id;
  @NotNull(message = "카테고리 이름은 필수 입니다. ")
  private String name;
  @NotNull(message = "부모 카테고리 아이디는 필수 입니다. ")
  private String parentId;

  public String getFullName() {
    return "/TEMP/";
  }

  public String getFullPath() {
    return "/TEMP/";
  }

}
