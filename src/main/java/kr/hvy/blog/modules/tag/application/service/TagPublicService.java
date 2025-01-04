package kr.hvy.blog.modules.tag.application.service;

import java.util.List;
import kr.hvy.blog.modules.tag.application.port.in.TagPublicUseCase;
import kr.hvy.blog.modules.tag.application.port.out.TagManagementPort;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.domain.TagMapper;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;

@UseCase
@RequiredArgsConstructor
public class TagPublicService implements TagPublicUseCase {

  private final TagMapper tagMapper;
  private final TagManagementPort tagManagementPort;

  @Override
  public List<TagResponse> getAllTags() {
    List<Tag> tags = tagManagementPort.getAllTags();
    return tags.stream()
        .map(tagMapper::toResponse)
        .toList();
  }

  @Override
  public List<TagResponse> findByNameContainingOrderByName(String name) {
    List<Tag> tags = tagManagementPort.findByNameContainingOrderByName(name);
    return tags.stream()
        .map(tagMapper::toResponse)
        .toList();
  }



}
