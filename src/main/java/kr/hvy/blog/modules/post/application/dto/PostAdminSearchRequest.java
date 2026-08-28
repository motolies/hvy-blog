package kr.hvy.blog.modules.post.application.dto;

import java.time.LocalDate;
import kr.hvy.common.application.domain.dto.paging.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 관리자 글 목록 검색 요청.
 * <p>
 * 공개 검색({@code GET /api/post/search?query=<base64>})을 재사용하지 않는 이유:
 * <ul>
 *   <li>공개 검색의 {@code SearchObject} 는 {@code searchCondition.keywords[]} 같은 중첩 구조인데,
 *       프론트의 {@code useServerGrid} 는 평평한 키/값을 POST 로 보낸다. 변환 계층을 두면
 *       7개 관리자 화면이 공유하는 그리드 규약이 이 화면에서만 깨진다.</li>
 *   <li>status 필터·hasDraft 노출은 관리자 관심사다. 공개 DTO 에 넣으면
 *       "비관리자에겐 항상 false" 같은 조건부 필드가 공개 계약에 남는다.</li>
 * </ul>
 * 대신 SQL 본문은 PostMapper.xml 안에서 재사용하며, 기존 findBySearchObject 는 건드리지 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class PostAdminSearchRequest extends PageRequest {

  private String subject;
  private String categoryId;
  private String tagName;
  /** 'TEM' | 'PUB'. Enum 이 아니라 String 으로 받는다 — 아래 Criteria 주석 참고. */
  private String status;
  private Boolean publicAccess;
  /** true 면 미반영 초안이 있는 글만. */
  private Boolean hasDraft;
  private Integer minViewCount;
  private Integer maxViewCount;
  private LocalDate createdAtFrom;
  private LocalDate createdAtTo;
}
