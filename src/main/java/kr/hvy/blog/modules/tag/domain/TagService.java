package kr.hvy.blog.modules.tag.domain;

import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
import kr.hvy.blog.modules.tag.domain.specification.TagCreateSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TagService {

  private final TagMapper tagMapper;
  private final TagCreateSpecification tagCreateSpecification = new TagCreateSpecification();

  public Tag create(TagCreate createDto) {
    tagCreateSpecification.validateException(createDto);
    return tagMapper.toDomain(createDto);
  }

}
