package kr.hvy.blog.modules.post.repository;

import kr.hvy.blog.modules.post.domain.entity.PostDraft;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostDraftRepository extends JpaRepository<PostDraft, Long> {

}
