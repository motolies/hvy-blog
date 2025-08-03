package kr.hvy.blog.modules.category.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class CategorySingleResponse {

  String id;
  String name;
  int order;
  String fullPath;
  String parentId;
  int postCount;
}
