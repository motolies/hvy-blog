package kr.hvy.blog.modules.post.domain.dto;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

@Data
public class SearchElementDto implements Serializable {
  @Serial
  private static final long serialVersionUID = -7032938059288406829L;

  private String id;

  private String name;

}
