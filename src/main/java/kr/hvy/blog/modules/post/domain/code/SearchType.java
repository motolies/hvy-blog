package kr.hvy.blog.modules.post.domain.code;


import kr.hvy.common.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SearchType implements EnumCode<String> {

  TITLE("TITLE", "제목"),
  CONTENT("CONTENT", "본문"),
  FULL("FULL", "제목+본문");

  private final String code;
  private final String desc;
}
