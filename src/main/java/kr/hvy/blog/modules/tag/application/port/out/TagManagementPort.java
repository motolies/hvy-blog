package kr.hvy.blog.modules.tag.application.port.out;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.tag.domain.Tag;

public interface TagManagementPort {

  Optional<Tag> findById(Long id);

  List<Tag> getAllTags();

  List<Tag> findByNameContainingOrderByName(String name);

  Tag save(Tag tag);

  Long deleteById(Long id);
}
