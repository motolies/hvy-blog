package kr.hvy.blog.modules.post.domain.code;

import kr.hvy.common.code.CommonEnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PostStatus implements CommonEnumCode<String> {

  TEMP("TEMP", "임시저장"),
  PUBLISH("PUBLISH", "배포완료");

  private final String code;
  private final String desc;
}
