package kr.hvy.blog.modules.tag.adapter.out.persistence;

import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.tag.adapter.out.entity.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaTagRepository extends JpaRepository<TagEntity, Long> {

  Set<TagEntity> findByNameContainingOrderByName(String name);

  Set<TagEntity> findByIdIn(Set<Long> ids);

  Set<TagEntity> findAllByOrderByName();

  Optional<TagEntity> findByName(String name);

}
