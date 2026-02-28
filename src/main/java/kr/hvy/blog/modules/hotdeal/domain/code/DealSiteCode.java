package kr.hvy.blog.modules.hotdeal.domain.code;

import kr.hvy.common.core.code.base.EnumCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DealSiteCode implements EnumCode<String> {

  PPOMPPU("PPOMPPU", "뽐뿌"),
  CLIEN("CLIEN", "클리앙"),
  RULIWEB("RULIWEB", "루리웹");

  private final String code;
  private final String desc;
}
