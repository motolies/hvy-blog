package kr.hvy.blog.modules.tag.repository;

import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.tag.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, Long> {

  Set<Tag> findByNameContainingOrderByName(String name);

  Set<Tag> findByIdIn(Set<Long> ids);

  Set<Tag> findAllByOrderByName();

  Optional<Tag> findByName(String name);

  @Modifying
  @Query(value = "DELETE FROM tb_tag WHERE id NOT IN (SELECT DISTINCT tag_id FROM tb_post_tag_map)", nativeQuery = true)
  int deleteUnusedTags();

}
