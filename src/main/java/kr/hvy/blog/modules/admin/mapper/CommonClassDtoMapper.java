package kr.hvy.blog.modules.admin.mapper;

import java.util.List;
import kr.hvy.blog.modules.admin.application.dto.CommonClassCreate;
import kr.hvy.blog.modules.admin.application.dto.CommonClassResponse;
import kr.hvy.blog.modules.admin.domain.entity.CommonClass;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * CommonClass 엔티티 ↔ DTO 매핑 인터페이스
 */
@Mapper(componentModel = "spring", uses = {CommonCodeDtoMapper.class})
public interface CommonClassDtoMapper {

  /**
   * 생성 DTO → 엔티티 변환
   */
  @Mapping(target = "created", ignore = true)
  @Mapping(target = "updated", ignore = true)
  @Mapping(target = "codes", ignore = true)
  CommonClass toDomain(CommonClassCreate createDto);

  /**
   * 엔티티 → 응답 DTO 변환
   */
  @Named("toResponse")
  @Mapping(source = "created.at", target = "createdAt")
  @Mapping(source = "created.by", target = "createdBy")
  @Mapping(source = "updated.at", target = "updatedAt")
  @Mapping(source = "updated.by", target = "updatedBy")
  @Mapping(target = "codeCount", expression = "java(entity.getCodes() != null ? entity.getCodes().size() : 0)")
  CommonClassResponse toResponse(CommonClass entity);

  /**
   * 엔티티 리스트 → 응답 DTO 리스트 변환
   */
  @IterableMapping(qualifiedByName = "toResponse")
  List<CommonClassResponse> toResponseList(List<CommonClass> entities);

  /**
   * 코드 정보 없는 간단한 응답 DTO 변환
   */
  @Mapping(source = "created.at", target = "createdAt")
  @Mapping(source = "created.by", target = "createdBy")
  @Mapping(source = "updated.at", target = "updatedAt")
  @Mapping(source = "updated.by", target = "updatedBy")
  @Mapping(target = "codes", ignore = true)
  @Mapping(target = "codeCount", constant = "0")
  CommonClassResponse toSimpleResponse(CommonClass entity);
}
