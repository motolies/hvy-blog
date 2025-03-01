package kr.hvy.blog.modules.tag.application.service;

import kr.hvy.blog.modules.tag.application.port.in.TagManagementUseCase;
import kr.hvy.blog.modules.tag.application.port.out.TagManagementPort;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.domain.TagMapper;
import kr.hvy.blog.modules.tag.domain.TagService;
import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.layer.UseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@Transactional
@RequiredArgsConstructor
public class TagManagementService implements TagManagementUseCase {

  private final TagService tagService;
  private final TagMapper tagMapper;
  private final TagManagementPort tagManagementPort;


  @Override
  public TagResponse create(TagCreate createDto) {
    Tag newTag = tagService.create(createDto);
    Tag savedTag = tagManagementPort.save(newTag);
    return tagMapper.toResponse(savedTag);
  }

  @Override
  public DeleteResponse<Long> delete(Long tagId) {
    tagManagementPort.deleteById(tagId);
    return DeleteResponse.<Long>builder()
        .id(tagId).build();
  }
}
