package kr.hvy.blog.modules.post.framework.out.persistence;

import java.util.List;
import kr.hvy.blog.modules.post.framework.out.entity.PostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPostRepository extends JpaRepository<PostEntity, Long> {

  List<PostEntity> findByMainPage(boolean main);

  List<PostEntity> findByPublicAccess(boolean isPublic);
}
