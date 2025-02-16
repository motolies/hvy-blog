package kr.hvy.blog.modules.tag.adapter.out;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.tag.application.port.out.TagManagementPort;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.domain.TagMapper;
import kr.hvy.blog.modules.tag.adapter.out.entity.TagEntity;
import kr.hvy.blog.modules.tag.adapter.out.persistence.JpaTagRepository;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

@OutputAdapter
@RequiredArgsConstructor
public class TagManagementAdapter implements TagManagementPort {

  @PersistenceContext
  private EntityManager entityManager; // EntityManager 주입

  private final TagMapper tagMapper;
  private final JpaTagRepository jpaTagRepository;

  @Override
  public Optional<Tag> findById(Long id) {
    return jpaTagRepository.findById(id)
        .map(tagEntity -> {
          entityManager.refresh(tagEntity); // 엔티티 갱신
          return tagMapper.toDomain(tagEntity);
        });
  }

  @Override
  public List<Tag> getAllTags() {
    return jpaTagRepository.findAll()
        .stream()
        .map(tagMapper::toDomain)
        .toList();
  }

  @Override
  public List<Tag> findByNameContainingOrderByName(String name) {
    Set<TagEntity> list;
    if (StringUtils.isBlank(name)) {
      list = jpaTagRepository.findAllByOrderByName();
    } else {
      list = jpaTagRepository.findByNameContainingOrderByName(name);
    }

    return list.stream()
        .map(tagMapper::toDomain)
        .toList();
  }

  @Override
  public Tag save(Tag tag) {
    TagEntity returnTag = jpaTagRepository.findByName(tag.getName())
        .orElseGet(() -> jpaTagRepository.save(tagMapper.toEntity(tag)));
    return tagMapper.toDomain(returnTag);
  }

  @Override
  public Long deleteById(Long id) {
    jpaTagRepository.deleteById(id);
    return id;
  }
}
