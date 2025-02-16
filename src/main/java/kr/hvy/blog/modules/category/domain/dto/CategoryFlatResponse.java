package kr.hvy.blog.modules.category.domain.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryFlatResponse {

  private String id;
  private String name;
  private int order;
  private String fullName;
  private String parentId;

  @JsonGetter
  private String getTreeName() {

    Character c = (char) Integer.parseInt("3000", 16);

    long level = this.fullName.chars().filter(f -> f == '/').count() - 2;
    if (level == 0) {
      return this.name;
    }
    String prefixLevel = "";
    for (int i = 0; i < level; i++) {
      // 공백 특수문자

      prefixLevel += c;
    }
    return prefixLevel + "└─" + c + this.name;
  }

  public String getLabel() {
    return name;
  }
}
