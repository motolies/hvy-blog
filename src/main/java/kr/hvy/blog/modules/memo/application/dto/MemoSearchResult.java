package kr.hvy.blog.modules.memo.application.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoSearchResult {

  private Long id;
  private String content;
  private boolean deleted;
  private Long categoryId;
  private String categoryName;
  private int categorySeq;
  private LocalDateTime createdAt;
  private String createdBy;
  private LocalDateTime updatedAt;
  private String updatedBy;
}
