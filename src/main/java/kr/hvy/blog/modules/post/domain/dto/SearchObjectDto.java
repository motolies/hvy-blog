package kr.hvy.blog.modules.post.domain.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import kr.hvy.blog.modules.post.domain.code.SearchType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SearchObjectDto implements Serializable {

  @Serial
  private static final long serialVersionUID = 4289845041651026684L;

  private SearchType searchType;

  private SearchConditionDto searchCondition;

  // [{"id": "ROOT", "name": "전체글"}]
  private List<SearchElementDto> categories;

  // [{"id": "1", "name": "Java"}]
  private List<SearchElementDto> tags;

  public int getOffset() {
    return (page - 1) * pageSize;
  }

  private int page;

  private int pageSize;

}
