package kr.hvy.blog.modules.file.mapper;


import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.file.application.dto.FileCreate;
import kr.hvy.blog.modules.file.application.dto.FileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface FileDtoMapper {

  FileDtoMapper INSTANCE = Mappers.getMapper(FileDtoMapper.class);

  // File -> FileResponse 매핑 시 file.hexId 값을 fileResponse.id에 매핑
  @Mapping(source = "hexId", target = "id")
  FileResponse toResponse(File file);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "post", ignore = true)
  @Mapping(target = "path", ignore = true)
  @Mapping(target = "fileSize", ignore = true)
  @Mapping(target = "deleted", ignore = true)
  @Mapping(target = "created", ignore = true)
  File toDomain(FileCreate fileCreate);

}
