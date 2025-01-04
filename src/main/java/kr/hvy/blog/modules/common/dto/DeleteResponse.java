package kr.hvy.blog.modules.common.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeleteResponse<T> {
  private T id;
}
