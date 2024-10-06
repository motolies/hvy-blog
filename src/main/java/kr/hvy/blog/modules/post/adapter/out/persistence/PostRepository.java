package kr.hvy.blog.modules.post.adapter.out.persistence;

import kr.hvy.blog.modules.post.domain.entity.PostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<PostEntity, Long> {

}
