package kr.hvy.blog.modules.common.notify.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SlackChannel implements EnumCode<String> {
  NOTIFY("NOTIFY", "알림채널", "#hvy-notify"),
  ERROR("ERROR", "에러채널", "#hvy-error"),
  HOT_DEAL("HOTDEAL", "핫딜", "#hvy-hotdeal"),
  /** AI 시장 판단·추천·주간 보고 전용 채널 (2026-09-13 advisor 모듈). 워크스페이스에 채널을 먼저 만들어야 한다 */
  ADVISOR("ADVISOR", "투자 실험 알림", "#hvy-advisor");

  private final String code;
  private final String desc;
  private final String channel;
}
