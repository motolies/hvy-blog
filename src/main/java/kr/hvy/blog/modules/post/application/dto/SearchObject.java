package kr.hvy.blog.modules.post.application.dto;

import java.util.List;
import kr.hvy.blog.modules.post.domain.code.SearchType;
import kr.hvy.common.application.domain.dto.paging.PageRequest;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Value
@SuperBuilder(toBuilder = true)
@Jacksonized
@EqualsAndHashCode(callSuper = true)
public class SearchObject extends PageRequest {

  SearchType searchType;

  SearchCondition searchCondition;

  // [{"id": "ROOT", "name": "전체글"}]
  List<SearchElement> categories;

  // [{"id": "1", "name": "Java"}]
  List<SearchElement> tags;

}
