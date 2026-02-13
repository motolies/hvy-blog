package kr.hvy.blog.modules.memo.mapper;

import kr.hvy.blog.modules.memo.application.dto.MemoCategoryCreate;
import kr.hvy.blog.modules.memo.application.dto.MemoCategoryResponse;
import kr.hvy.blog.modules.memo.domain.entity.MemoCategory;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MemoCategoryDtoMapper {

  MemoCategoryResponse toResponse(MemoCategory entity);

  MemoCategory toDomain(MemoCategoryCreate dto);
}
