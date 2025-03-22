package kr.hvy.blog.modules.common;

import kr.hvy.common.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SlackChannel implements EnumCode<String> {
  NOTIFY("NOTIFY", "알림채널", "#hvy-notify"),
  ERROR("ERROR", "에러채널", "#hvy-error"),
  HOT_DEAL("HOTDEAL", "핫딜", "#hvy-hotdeal");

  private final String code;
  private final String desc;
  private final String channel;
}
