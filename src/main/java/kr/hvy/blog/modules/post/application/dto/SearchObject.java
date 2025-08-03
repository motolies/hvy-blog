package kr.hvy.blog.modules.post.application.dto;

import java.util.List;
import kr.hvy.blog.modules.post.domain.code.SearchType;
import kr.hvy.common.domain.dto.paging.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
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
