package kr.hvy.blog.modules.tag.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Set;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.application.specification.TagCreateSpecification;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.mapper.TagDtoMapper;
import kr.hvy.blog.modules.tag.repository.TagRepository;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.specification.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TagService {

  @PersistenceContext
  private EntityManager entityManager; // EntityManager 주입

  private final TagDtoMapper tagDtoMapper;
  private final TagRepository tagRepository;


  public Tag findById(Long id) {
    return tagRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("Not Found Tag."));
  }

  public List<TagResponse> getAllTags() {
    return tagRepository.findAll().stream()
        .map(tagDtoMapper::toResponse)
        .toList();
  }

  public List<TagResponse> findByNameContainingOrderByName(String name) {

    Set<Tag> list;
    if (StringUtils.isBlank(name)) {
      list = tagRepository.findAllByOrderByName();
    } else {
      list = tagRepository.findByNameContainingOrderByName(name);
    }

    return list.stream()
        .map(tagDtoMapper::toResponse)
        .toList();
  }

  public TagResponse create(TagCreate createDto) {
    Specification.validate(TagCreateSpecification::new, createDto);

    Tag tag = tagRepository.findByName(createDto.getName())
        .orElseGet(() -> tagRepository.save(tagDtoMapper.toDomain(createDto)));
//    entityManager.refresh(tag);

    return tagDtoMapper.toResponse(tag);
  }

  public DeleteResponse<Long> delete(Long id) {
    tagRepository.deleteById(id);

    return DeleteResponse.<Long>builder()
        .id(id).build();
  }


}
