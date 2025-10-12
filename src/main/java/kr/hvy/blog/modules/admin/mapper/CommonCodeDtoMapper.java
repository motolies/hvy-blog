package kr.hvy.blog.modules.admin.mapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeCreate;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeTreeResponse;
import kr.hvy.blog.modules.admin.domain.entity.CommonCode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * CommonCode 엔티티 ↔ DTO 매핑 인터페이스
 */
@Mapper(componentModel = "spring", uses = {CommonClassDtoMapper.class})
public interface CommonCodeDtoMapper {

  /**
   * 생성 DTO → 엔티티 변환
   */
  @Mapping(target = "created", ignore = true)
  @Mapping(target = "updated", ignore = true)
  @Mapping(target = "commonClass", ignore = true)
  @Mapping(target = "childClass", ignore = true)
  CommonCode toDomain(CommonCodeCreate createDto);

  /**
   * 엔티티 → 응답 DTO 변환
   */
  @Mapping(source = "created.at", target = "createdAt")
  @Mapping(source = "created.by", target = "createdBy")
  @Mapping(source = "updated.at", target = "updatedAt")
  @Mapping(source = "updated.by", target = "updatedBy")
  @Mapping(source = "commonClass.code", target = "classCode")
  @Mapping(target = "attributes", source = ".", qualifiedByName = "mapAttributes")
  @Mapping(target = "children", ignore = true)
  @Mapping(target = "commonClass", ignore = true)
  CommonCodeResponse toResponse(CommonCode entity);

  /**
   * 엔티티 리스트 → 응답 DTO 리스트 변환
   */
  List<CommonCodeResponse> toResponseList(List<CommonCode> entities);

  /**
   * 엔티티 → 트리 아이템 DTO 변환
   */
  @Mapping(target = "attributes", source = ".", qualifiedByName = "mapAttributes")
  @Mapping(target = "children", ignore = true)
  CommonCodeTreeResponse.CommonCodeItemResponse toTreeItemResponse(CommonCode entity);

  /**
   * 엔티티 리스트 → 트리 아이템 DTO 리스트 변환
   */
  List<CommonCodeTreeResponse.CommonCodeItemResponse> toTreeItemResponseList(List<CommonCode> entities);

  /**
   * 엔티티 → 플랫 응답 DTO 변환
   */
  @Mapping(target = "attributes", source = ".", qualifiedByName = "mapAttributes")
  @Mapping(target = "fullPath", ignore = true)
  @Mapping(target = "level", ignore = true)
  CommonCodeTreeResponse.CommonCodeFlatResponse toFlatResponse(CommonCode entity);

  /**
   * 엔티티 리스트 → 플랫 응답 DTO 리스트 변환
   */
  List<CommonCodeTreeResponse.CommonCodeFlatResponse> toFlatResponseList(List<CommonCode> entities);

  /**
   * 동적 속성값들을 Map으로 변환
   */
  @Named("mapAttributes")
  default Map<String, String> mapAttributes(CommonCode entity) {
    Map<String, String> attributes = new HashMap<>();

    if (entity.getAttribute1Value() != null) {
      String key = (entity.getCommonClass() != null && entity.getCommonClass().getAttribute1Name() != null)
          ? entity.getCommonClass().getAttribute1Name() : "attribute1";
      attributes.put(key, entity.getAttribute1Value());
    }

    if (entity.getAttribute2Value() != null) {
      String key = (entity.getCommonClass() != null && entity.getCommonClass().getAttribute2Name() != null)
          ? entity.getCommonClass().getAttribute2Name() : "attribute2";
      attributes.put(key, entity.getAttribute2Value());
    }

    if (entity.getAttribute3Value() != null) {
      String key = (entity.getCommonClass() != null && entity.getCommonClass().getAttribute3Name() != null)
          ? entity.getCommonClass().getAttribute3Name() : "attribute3";
      attributes.put(key, entity.getAttribute3Value());
    }

    if (entity.getAttribute4Value() != null) {
      String key = (entity.getCommonClass() != null && entity.getCommonClass().getAttribute4Name() != null)
          ? entity.getCommonClass().getAttribute4Name() : "attribute4";
      attributes.put(key, entity.getAttribute4Value());
    }

    if (entity.getAttribute5Value() != null) {
      String key = (entity.getCommonClass() != null && entity.getCommonClass().getAttribute5Name() != null)
          ? entity.getCommonClass().getAttribute5Name() : "attribute5";
      attributes.put(key, entity.getAttribute5Value());
    }

    return attributes.isEmpty() ? null : attributes;
  }
}
