package kr.hvy.blog.modules.hotdeal.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class HotDealKeywordResponse {

  Long id;
  String keyword;

  /**
   * 실제 매칭에 사용되는 정규화 값. 관리자가 "왜 안 걸리는지"를 확인할 수 있도록 노출한다.
   */
  String normalizedKeyword;

  boolean enabled;
  Instant createdAt;
  Instant updatedAt;
}
