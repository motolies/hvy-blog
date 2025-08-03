package kr.hvy.blog.modules.post.application.dto;

import java.util.List;
import kr.hvy.blog.modules.post.domain.code.LogicalOperation;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class SearchCondition {

  LogicalOperation logic;

  // [{"name": "검색어"}]
  List<SearchElement> keywords;

}
