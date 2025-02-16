package kr.hvy.blog.modules.file.domain;

import kr.hvy.blog.modules.file.adapter.out.entity.FileEntity;
import kr.hvy.blog.modules.file.domain.dto.FileCreate;
import kr.hvy.blog.modules.file.domain.dto.FileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface FileMapper {

  FileMapper INSTANCE = Mappers.getMapper(FileMapper.class);

  FileEntity toEntity(File file);

  // File -> FileResponse 매핑 시 file.hexId 값을 fileResponse.id에 매핑
  @Mapping(source = "hexId", target = "id")
  FileResponse toResponse(File file);

  // FileResponse -> File 매핑 시 fileResponse.id 값을 file.hexId에 매핑
  @Mapping(source = "id", target = "hexId")
  File toEntity(FileResponse fileResponse);

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
