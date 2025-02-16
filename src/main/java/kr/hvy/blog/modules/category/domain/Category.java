package kr.hvy.blog.modules.category.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class Category {

  String id;
  String name;
  int order;
  String fullName;
  String fullPath;
  String parentId;
  @JsonProperty("children")
  List<Category> categories;

}
