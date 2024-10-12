package kr.hvy.blog.modules.post.adapter.out.persistence;

import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPostRepository extends JpaRepository<PostEntity, Long> {

}
