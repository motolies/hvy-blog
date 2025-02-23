package kr.hvy.blog.modules.post.domain.code;


import kr.hvy.common.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PostStatus implements EnumCode<String> {

  TEMP("TEM", "임시저장"),
  PUBLISH("PUB", "배포완료");

  private final String code;
  private final String desc;
}
