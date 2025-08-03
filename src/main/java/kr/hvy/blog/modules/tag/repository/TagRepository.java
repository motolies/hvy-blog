package kr.hvy.blog.modules.tag.repository;

import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.tag.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

  Set<Tag> findByNameContainingOrderByName(String name);

  Set<Tag> findByIdIn(Set<Long> ids);

  Set<Tag> findAllByOrderByName();

  Optional<Tag> findByName(String name);

}
