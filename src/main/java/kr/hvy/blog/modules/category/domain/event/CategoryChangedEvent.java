package kr.hvy.blog.modules.category.domain.event;

/**
 * 카테고리 변경 도메인 이벤트
 * 카테고리가 생성 또는 수정될 때 발행됩니다.
 */
public record CategoryChangedEvent(String categoryId) {

}
