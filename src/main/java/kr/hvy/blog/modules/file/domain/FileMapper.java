package kr.hvy.blog.modules.file.domain;

import kr.hvy.blog.modules.file.domain.dto.FileCreate;
import kr.hvy.blog.modules.file.domain.dto.FileResponse;
import kr.hvy.blog.modules.file.framework.out.entity.FileEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface FileMapper {

  FileMapper INSTANCE = Mappers.getMapper(FileMapper.class);

  FileEntity toEntity(File file);

  FileResponse toResponse(File file);

  File toDomain(FileCreate fileCreate);

  // 맵핑에서 제외할 속성들
  @Mapping(target = "post.files", ignore = true)
  @Mapping(target = "post.tags", ignore = true)
  File toDomain(FileEntity fileEntity);

  @ObjectFactory
  default File.FileBuilder createFileBuilder() {
    return File.builder();
  }

}
