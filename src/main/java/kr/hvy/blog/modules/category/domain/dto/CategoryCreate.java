package kr.hvy.blog.modules.category.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCreate {

  private String id;
  private String name;
  private String parentId;

  public String getFullName() {
    return "/TEMP/";
  }

  public String getFullPath() {
    return "/TEMP/";
  }

}
