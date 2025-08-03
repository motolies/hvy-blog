package kr.hvy.blog.modules.category.application.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class CategoryFlatResponse {

  String id;
  String name;
  int order;
  String fullName;
  String parentId;

  @JsonGetter
  public String getTreeName() {

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
