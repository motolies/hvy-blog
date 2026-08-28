package kr.hvy.blog.modules.post.application.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 관리자 글 목록 1행.
 * <p>
 * {@code status} 를 PostStatus enum 이 아니라 String 으로 두는 이유:
 * PostStatus 는 EnumCode + JPA AttributeConverter 조합이라 MyBatis resultType 으로 쓰면
 * 타입핸들러 매핑 문제가 생긴다. 'TEM'/'PUB' 문자열을 프론트가 라벨로 바꾸는 편이 단순하고 안전하다.
 */
@Value
@Builder
@Jacksonized
public class PostAdminSearchResponse {

  long id;
  String subject;
  String categoryId;
  String categoryName;
  String status;
  boolean publicAccess;
  boolean mainPage;
  /** 발행본에 반영되지 않은 초안이 있는가. 현재 UI 어디에서도 보이지 않던 정보다. */
  boolean hasDraft;
  int viewCount;
  long tagCount;
  Instant createdAt;
  Instant updatedAt;
}
