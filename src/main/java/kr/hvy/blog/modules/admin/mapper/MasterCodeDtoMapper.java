package kr.hvy.blog.modules.admin.mapper;

import java.util.List;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeTreeResponse;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MasterCode 엔티티 <-> DTO 매핑 인터페이스
 */
@Mapper(componentModel = "spring")
public interface MasterCodeDtoMapper {

  /**
   * 엔티티 -> 응답 DTO 변환
   */
  @Named("toResponse")
  @Mapping(source = "created.at", target = "createdAt")
  @Mapping(source = "created.by", target = "createdBy")
  @Mapping(source = "updated.at", target = "updatedAt")
  @Mapping(source = "updated.by", target = "updatedBy")
  @Mapping(source = "parent.id", target = "parentId")
  @Mapping(target = "childCount", expression = "java(entity.getChildren() != null ? entity.getChildren().size() : 0)")
  MasterCodeResponse toResponse(MasterCode entity);

  /**
   * 엔티티 리스트 -> 응답 DTO 리스트 변환
   */
  @IterableMapping(qualifiedByName = "toResponse")
  List<MasterCodeResponse> toResponseList(List<MasterCode> entities);

  /**
   * 엔티티 -> 트리 노드 DTO 변환 (children은 서비스에서 수동 설정)
   */
  @Named("toTreeNode")
  @Mapping(target = "children", ignore = true)
  MasterCodeTreeResponse toTreeNode(MasterCode entity);
}
