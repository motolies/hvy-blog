package kr.hvy.blog.modules.category.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCreate {

  @JsonIgnore
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
