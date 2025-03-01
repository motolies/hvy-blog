package kr.hvy.blog.modules.post.domain.dto;

import java.util.List;
import kr.hvy.blog.modules.post.domain.code.SearchType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchObject {

  private SearchType searchType;

  private SearchCondition searchCondition;

  // [{"id": "ROOT", "name": "전체글"}]
  private List<SearchElement> categories;

  // [{"id": "1", "name": "Java"}]
  private List<SearchElement> tags;

  public int getOffset() {
    return (page - 1) * pageSize;
  }

  private int page;

  private int pageSize;

}
