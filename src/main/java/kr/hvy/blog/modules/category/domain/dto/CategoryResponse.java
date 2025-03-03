package kr.hvy.blog.modules.category.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryResponse {

  private String id;
  private String name;
  private int order;
  private String fullPath;
  private String parentId;
  private int postCount;
  @JsonProperty("children")
  List<CategoryResponse> categories;
}
