package kr.hvy.blog.modules.post.domain.dto;

import java.util.List;
import kr.hvy.blog.modules.post.domain.code.LogicalOperation;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchCondition {

  private LogicalOperation logic;

  // [{"name": "검색어"}]
  private List<SearchElement> keywords;

}
