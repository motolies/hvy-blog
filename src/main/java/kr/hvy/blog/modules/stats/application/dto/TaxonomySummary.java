package kr.hvy.blog.modules.stats.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * 분류 체계·첨부 집계.
 * <p>
 * 기존 {@code countCategories} 는 {@code COUNT(DISTINCT category_id)} 로 "사용 중인" 카테고리를
 * 세면서 라벨은 "카테고리 수"였다. 여기서는 두 값을 분리해 라벨이 거짓말하지 않게 한다.
 */
@Value
@Builder
@Jacksonized
public class TaxonomySummary {

  long totalCategories;
  long totalTags;
  long usedTags;
  /** 미사용 태그 = totalTags - usedTags. "정리할 태그 N개"라는 실제로 행동 가능한 신호. */
  long unusedTags;
  /** 발행된 글에 저장만 되고 반영되지 않은 초안 수. */
  long draftCount;
  long fileCount;
  long fileTotalSize;
}
