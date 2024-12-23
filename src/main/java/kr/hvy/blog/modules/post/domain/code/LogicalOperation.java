package kr.hvy.blog.modules.post.domain.code;

import kr.hvy.common.code.CommonEnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LogicalOperation implements CommonEnumCode<String> {

  AND("AND", "AND"),
  OR("OR", "OR");

  private final String code;
  private final String desc;
}
