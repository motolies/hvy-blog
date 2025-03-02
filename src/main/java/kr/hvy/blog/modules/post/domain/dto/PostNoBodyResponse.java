package kr.hvy.blog.modules.post.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostNoBodyResponse {

  private int id;
  private String subject;
  private String categoryName;
  private int viewCount;
  private java.sql.Timestamp createDate;
  private java.sql.Timestamp updateDate;

}

