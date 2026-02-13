package kr.hvy.blog.modules.memo.mapper;

import kr.hvy.blog.modules.memo.application.dto.MemoResponse;
import kr.hvy.blog.modules.memo.domain.entity.Memo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {MemoCategoryDtoMapper.class})
public interface MemoDtoMapper {

  @Mapping(source = "hexId", target = "id")
  MemoResponse toResponse(Memo entity);
}
