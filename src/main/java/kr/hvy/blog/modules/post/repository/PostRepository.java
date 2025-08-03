package kr.hvy.blog.modules.post.repository;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.post.domain.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, Long> {

  List<Post> findByMainPage(boolean main);

  Optional<Post> findTopByPublicAccessOrderById(boolean isPublic);

  List<Post> findBySubjectAndBody(String subject, String body);
}
