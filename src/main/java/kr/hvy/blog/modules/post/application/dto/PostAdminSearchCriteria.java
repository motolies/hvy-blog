package kr.hvy.blog.modules.post.application.dto;

import java.time.Instant;
import kr.hvy.common.application.domain.dto.paging.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** 매퍼에 넘기는 검색 조건. 날짜는 브라우저 로컬 → UTC 반개구간으로 변환된 상태다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class PostAdminSearchCriteria extends PageRequest {

  private String subject;
  private String categoryId;
  private String tagName;
  private String status;
  private Boolean publicAccess;
  private Boolean hasDraft;
  private Integer minViewCount;
  private Integer maxViewCount;
  private Instant createdAtFrom;
  private Instant createdAtToExclusive;
}
