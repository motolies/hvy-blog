package kr.hvy.blog.modules.post.domain.dto;

import java.util.List;
import kr.hvy.blog.modules.post.domain.code.SearchType;
import kr.hvy.common.domain.dto.paging.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SearchObject extends PageRequest {

  private SearchType searchType;

  private SearchCondition searchCondition;

  // [{"id": "ROOT", "name": "전체글"}]
  private List<SearchElement> categories;

  // [{"id": "1", "name": "Java"}]
  private List<SearchElement> tags;

}
