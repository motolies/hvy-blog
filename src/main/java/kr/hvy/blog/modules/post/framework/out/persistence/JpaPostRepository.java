package kr.hvy.blog.modules.post.framework.out.persistence;

import kr.hvy.blog.modules.post.framework.out.entity.PostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPostRepository extends JpaRepository<PostEntity, Long> {

}
