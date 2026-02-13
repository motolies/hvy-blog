package kr.hvy.blog.modules.memo.application.dto;

import kr.hvy.common.application.domain.dto.paging.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class MemoSearchRequest extends PageRequest {

  private String keyword;
  private Long categoryId;
  private Boolean includeDeleted;
}
