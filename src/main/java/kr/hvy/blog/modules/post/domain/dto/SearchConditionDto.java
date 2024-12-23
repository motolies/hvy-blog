package kr.hvy.blog.modules.post.domain.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import kr.hvy.blog.modules.post.domain.code.LogicalOperation;
import lombok.Data;

@Data
public class SearchConditionDto implements Serializable {

  @Serial
  private static final long serialVersionUID = 6372195696197437659L;

  private LogicalOperation logic;

  // [{"name": "검색어"}]
  private List<SearchElementDto> keywords;

}
