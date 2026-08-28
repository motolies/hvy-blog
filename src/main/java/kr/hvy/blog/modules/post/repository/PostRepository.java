package kr.hvy.blog.modules.post.repository;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.blog.modules.post.domain.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  List<Post> findByMainPage(boolean main);

  Optional<Post> findTopByPublicAccessOrderById(boolean isPublic);

  List<Post> findBySubjectAndBody(String subject, String body);

  /**
   * 조회수를 원자적으로 1 증가시킨다.
   * <p>
   * WHERE 에 공개 조건을 포함하는 이유: 관리자 미리보기나 임시저장 글 조회가 통계를 오염시키면 안 된다.
   * 대상이 없으면 0을 반환하며, 호출부는 이를 예외가 아니라 정상 종료로 다뤄야 한다
   * (beacon 은 공개 엔드포인트이고, 예외를 던지면 Slack 알림까지 발송된다).
   * <p>
   * status 는 PostStatusConverter 경유이므로 리터럴이 아닌 파라미터로 바인딩해야 컨버터가 적용된다.
   */
  @Modifying(clearAutomatically = true)
  @Query("""
      UPDATE Post p
         SET p.viewCount = p.viewCount + 1
       WHERE p.id = :id
         AND p.status = :status
         AND p.publicAccess = true
      """)
  int incrementViewCount(@Param("id") Long id, @Param("status") PostStatus status);
}
