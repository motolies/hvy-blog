package kr.hvy.blog.modules.hotdeal.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class HotDealKeywordUpdate {

  @NotBlank(message = "키워드는 필수입니다.")
  @Size(max = 64, message = "키워드는 64자를 초과할 수 없습니다.")
  String keyword;

  boolean enabled;
}
