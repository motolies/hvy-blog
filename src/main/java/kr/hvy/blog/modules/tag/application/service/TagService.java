package kr.hvy.blog.modules.tag.application.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagMerge;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.application.dto.TagUpdate;
import kr.hvy.blog.modules.tag.application.specification.TagCreateSpecification;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.mapper.TagDtoMapper;
import kr.hvy.blog.modules.tag.repository.TagRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.specification.Specification;
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

  public Tag createIfNotExists(TagCreate createDto) {
    Specification.validate(TagCreateSpecification::new, createDto);
    return tagRepository.findByName(createDto.getName())
        .orElseGet(() -> tagRepository.save(tagDtoMapper.toDomain(createDto)));
  }

  public TagResponse create(TagCreate createDto) {
    return tagDtoMapper.toResponse(createIfNotExists(createDto));
  }

  public DeleteResponse<Long> delete(Long id) {
    tagRepository.deleteById(id);

    return DeleteResponse.<Long>builder()
        .id(id).build();
  }

  public TagResponse update(Long id, TagUpdate updateDto) {
    Tag tag = findById(id);
    tag.setName(updateDto.getName());
    return tagDtoMapper.toResponse(tagRepository.save(tag));
  }

  public Map<String, Integer> deleteUnused() {
    int deletedCount = tagRepository.deleteUnusedTags();
    log.info("미사용 태그 {}건 삭제", deletedCount);
    return Map.of("deletedCount", deletedCount);
  }

  @Transactional
  public TagResponse merge(TagMerge mergeDto) {
    Tag source = findById(mergeDto.getSourceTagId());
    Tag target = findById(mergeDto.getTargetTagId());

    // source의 포스트를 target에 추가 (이미 있으면 Set 특성상 무시됨)
    Set.copyOf(source.getPosts()).forEach(post -> {
      source.removePost(post);
      target.addPost(post);
    });

    tagRepository.delete(source);
    return tagDtoMapper.toResponse(tagRepository.save(target));
  }

}
