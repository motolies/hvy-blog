package kr.hvy.blog.modules.stats.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 최근 N일 인기 글 — beacon 로그(tb_system_log) 기반이라 "요즘 읽히는 글"을 답한다.
 * 누적 view_count 기반 {@link PopularPost} 는 "역대 많이 읽힌 글"이라 질문이 다르다.
 */
@Value
@Builder
@Jacksonized
public class RecentPopularPost {

  long id;
  String subject;
  String categoryName;
  long viewCount;
  long visitorCount;
}
