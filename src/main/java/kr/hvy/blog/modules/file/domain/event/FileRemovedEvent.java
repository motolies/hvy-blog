package kr.hvy.blog.modules.file.domain.event;

/**
 * 파일 삭제 도메인 이벤트
 * 파일 엔티티가 삭제될 때 발행됩니다.
 */
public record FileRemovedEvent(String filePath) {

}
