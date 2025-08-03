package kr.hvy.blog.modules.category.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class CategoryResponse {

  String id;
  String name;
  int order;
  String fullPath;
  String parentId;
  int postCount;
  @JsonProperty("children")
  List<CategoryResponse> categories;
}
