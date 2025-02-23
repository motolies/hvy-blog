package kr.hvy.blog.modules.post.domain.code;


import kr.hvy.common.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LogicalOperation implements EnumCode<String> {

  AND("AND", "AND"),
  OR("OR", "OR");

  private final String code;
  private final String desc;
}
