package kr.hvy.blog.modules.admin.mapper;

import java.util.List;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeResponse;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MasterCode 엔티티 <-> DTO 매핑 인터페이스.
 * <p>
 * DTO 는 hvy-common 으로 이동되어 blog-back 과 외부 소비 모듈이 공유한다.
 */
@Mapper(componentModel = "spring")
public interface MasterCodeDtoMapper {

  @Named("toResponse")
  @Mapping(source = "created.at", target = "createdAt")
  @Mapping(source = "created.by", target = "createdBy")
  @Mapping(source = "updated.at", target = "updatedAt")
  @Mapping(source = "updated.by", target = "updatedBy")
  @Mapping(source = "parent.id", target = "parentId")
  @Mapping(target = "childCount", expression = "java(entity.getChildren() != null ? entity.getChildren().size() : 0)")
  MasterCodeResponse toResponse(MasterCode entity);

  @IterableMapping(qualifiedByName = "toResponse")
  List<MasterCodeResponse> toResponseList(List<MasterCode> entities);

  @Named("toTreeNode")
  @Mapping(target = "children", ignore = true)
  MasterCodeTreeResponse toTreeNode(MasterCode entity);
}
