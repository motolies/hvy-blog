package kr.hvy.blog.modules.admin.application.service;

import kr.hvy.blog.modules.admin.application.dto.CommonClassResponse;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeTreeResponse;
import kr.hvy.blog.modules.admin.domain.entity.CommonClass;
import kr.hvy.blog.modules.admin.domain.entity.CommonCode;
import kr.hvy.blog.modules.admin.mapper.CommonClassDtoMapper;
import kr.hvy.blog.modules.admin.mapper.CommonCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.CommonClassRepository;
import kr.hvy.blog.modules.admin.repository.CommonCodeRepository;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 공통코드 공개 조회 서비스
 * 읽기 전용 작업 및 캐시 적용 담당
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommonCodePublicService {

  private final CommonClassRepository commonClassRepository;
  private final CommonCodeRepository commonCodeRepository;
  private final CommonClassDtoMapper commonClassDtoMapper;
  private final CommonCodeDtoMapper commonCodeDtoMapper;

  // ========== 클래스 조회 ==========

  /**
   * 모든 활성화된 클래스 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeClass", key = "'all'")
  public List<CommonClassResponse> getAllClasses() {
    List<CommonClass> entities = commonClassRepository.findActiveClassesOrderByName();
    return commonClassDtoMapper.toResponseList(entities);
  }

  /**
   * 특정 클래스 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeClass", key = "#className")
  public CommonClassResponse getClass(String className) {
    CommonClass entity = findClassByName(className);
    return commonClassDtoMapper.toResponse(entity);
  }

  // ========== 코드 조회 ==========

  /**
   * 클래스별 코드 목록 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeData", key = "#className")
  public List<CommonCodeResponse> getCodesByClass(String className) {
    // 클래스 존재 확인
    findClassByName(className);

    List<CommonCode> entities = commonCodeRepository.findByClassNameAndIsActiveTrueOrderBySortAscCodeAsc(className);
    return commonCodeDtoMapper.toResponseList(entities);
  }

  /**
   * 특정 코드 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeData", key = "'single:'+#className+':'+#code")
  public CommonCodeResponse getCode(String className, String code) {
    CommonCode entity = findCodeByClassNameAndCode(className, code);
    return commonCodeDtoMapper.toResponse(entity);
  }

  /**
   * 하위 코드 조회
   */
  public List<CommonCodeResponse> getChildCodes(String parentClassName, String parentCode) {
    CommonCode parentCodeEntity = findCodeByClassNameAndCode(parentClassName, parentCode);

    if (!parentCodeEntity.hasChildren() || parentCodeEntity.getChildClassName() == null) {
      return new ArrayList<>();
    }

    List<CommonCode> childEntities = commonCodeRepository.findChildCodes(parentClassName, parentCode);
    return commonCodeDtoMapper.toResponseList(childEntities);
  }

  // ========== 트리 구조 조회 ==========

  /**
   * 계층 구조 트리 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeTree", key = "#className")
  public CommonCodeTreeResponse getCodesWithTree(String className) {
    CommonClass classEntity = findClassByName(className);
    List<CommonCode> rootCodes = commonCodeRepository.findByClassNameAndIsActiveTrueOrderBySortAscCodeAsc(className);

    List<CommonCodeTreeResponse.CommonCodeItemResponse> treeItems = buildTreeStructure(rootCodes);

    return CommonCodeTreeResponse.builder()
        .className(classEntity.getName())
        .displayName(classEntity.getDisplayName())
        .codes(treeItems)
        .totalCount(treeItems.size())
        .build();
  }

  /**
   * 평면 구조 조회 (select box용)
   */
  public List<CommonCodeTreeResponse.CommonCodeFlatResponse> getFlatCodes(String className) {
    List<CommonCodeTreeResponse.CommonCodeFlatResponse> result = new ArrayList<>();
    buildFlatStructure(className, "", 0, result);
    return result;
  }

  // ========== 검색 ==========

  /**
   * 클래스 내 코드명 검색
   */
  public List<CommonCodeResponse> searchCodesInClass(String className, String searchTerm) {
    // 클래스 존재 확인
    findClassByName(className);

    List<CommonCode> entities = commonCodeRepository.searchByNameInClass(className, searchTerm);
    return commonCodeDtoMapper.toResponseList(entities);
  }

  /**
   * 전체 클래스에서 코드명 검색
   */
  public List<CommonCodeResponse> searchCodesAcrossAllClasses(String searchTerm) {
    List<CommonCode> entities = commonCodeRepository.searchByNameAcrossAllClasses(searchTerm);
    return commonCodeDtoMapper.toResponseList(entities);
  }

  /**
   * 속성값으로 코드 검색
   */
  public List<CommonCodeResponse> searchCodesByAttribute(String className, String attributeValue) {
    // 클래스 존재 확인
    findClassByName(className);

    List<CommonCode> entities = commonCodeRepository.findByAttributeValue(className, attributeValue);
    return commonCodeDtoMapper.toResponseList(entities);
  }

  // ========== 통계 ==========

  /**
   * 클래스별 코드 개수 통계
   */
  public Map<String, Long> getCodeCountByClass() {
    List<Object[]> results = commonCodeRepository.getCodeCountByClass();
    return results.stream()
        .collect(Collectors.toMap(
            result -> (String) result[0],  // className
            result -> (Long) result[1]     // count
        ));
  }

  // ========== 헬퍼 메서드 ==========

  private CommonClass findClassByName(String className) {
    return commonClassRepository.findByNameAndIsActiveTrue(className)
        .orElseThrow(() -> new DataNotFoundException("클래스를 찾을 수 없습니다: " + className));
  }

  private CommonCode findCodeByClassNameAndCode(String className, String code) {
    return commonCodeRepository.findByClassNameAndCodeAndIsActiveTrue(className, code)
        .orElseThrow(() -> new DataNotFoundException(
            String.format("코드를 찾을 수 없습니다: %s.%s", className, code)));
  }

  /**
   * 트리 구조 빌드 (재귀)
   */
  private List<CommonCodeTreeResponse.CommonCodeItemResponse> buildTreeStructure(List<CommonCode> codes) {
    List<CommonCodeTreeResponse.CommonCodeItemResponse> result = new ArrayList<>();

    for (CommonCode code : codes) {
      CommonCodeTreeResponse.CommonCodeItemResponse.CommonCodeItemResponseBuilder builder =
          CommonCodeTreeResponse.CommonCodeItemResponse.builder()
              .code(code.getCode())
              .name(code.getName())
              .description(code.getDescription())

              .childClassName(code.getChildClassName())
              .sort(code.getSort())
              .isActive(code.getIsActive());

      // 동적 속성 처리
      if (code.getCommonClass() != null) {
        Map<String, String> attributes = new HashMap<>();
        if (code.getAttribute1Value() != null && code.getCommonClass().getAttribute1Name() != null) {
          attributes.put(code.getCommonClass().getAttribute1Name(), code.getAttribute1Value());
        }
        if (code.getAttribute2Value() != null && code.getCommonClass().getAttribute2Name() != null) {
          attributes.put(code.getCommonClass().getAttribute2Name(), code.getAttribute2Value());
        }
        if (code.getAttribute3Value() != null && code.getCommonClass().getAttribute3Name() != null) {
          attributes.put(code.getCommonClass().getAttribute3Name(), code.getAttribute3Value());
        }
        if (code.getAttribute4Value() != null && code.getCommonClass().getAttribute4Name() != null) {
          attributes.put(code.getCommonClass().getAttribute4Name(), code.getAttribute4Value());
        }
        if (code.getAttribute5Value() != null && code.getCommonClass().getAttribute5Name() != null) {
          attributes.put(code.getCommonClass().getAttribute5Name(), code.getAttribute5Value());
        }
        builder.attributes(attributes.isEmpty() ? null : attributes);
      }

      // 하위 코드가 있는 경우 재귀적으로 로드
      if (code.hasChildren() && code.getChildClassName() != null) {
        List<CommonCode> childCodes = commonCodeRepository.findByClassNameAndIsActiveTrueOrderBySortAscCodeAsc(
            code.getChildClassName());
        List<CommonCodeTreeResponse.CommonCodeItemResponse> children = buildTreeStructure(childCodes);
        builder.children(children);
      }

      result.add(builder.build());
    }

    return result;
  }

  /**
   * 평면 구조 빌드 (재귀)
   */
  private void buildFlatStructure(String className, String pathPrefix, int level,
                                  List<CommonCodeTreeResponse.CommonCodeFlatResponse> result) {
    List<CommonCode> codes = commonCodeRepository.findByClassNameAndIsActiveTrueOrderBySortAscCodeAsc(className);

    for (CommonCode code : codes) {
      String currentPath = pathPrefix.isEmpty() ? code.getName() : pathPrefix + " > " + code.getName();

      // 동적 속성 맵 생성
      Map<String, String> attributes = new HashMap<>();
      if (code.getAttribute1Value() != null) attributes.put("attribute1", code.getAttribute1Value());
      if (code.getAttribute2Value() != null) attributes.put("attribute2", code.getAttribute2Value());
      if (code.getAttribute3Value() != null) attributes.put("attribute3", code.getAttribute3Value());
      if (code.getAttribute4Value() != null) attributes.put("attribute4", code.getAttribute4Value());
      if (code.getAttribute5Value() != null) attributes.put("attribute5", code.getAttribute5Value());

      CommonCodeTreeResponse.CommonCodeFlatResponse flatItem = CommonCodeTreeResponse.CommonCodeFlatResponse.builder()
          .fullPath(currentPath)
          .className(code.getClassName())
          .code(code.getCode())
          .name(code.getName())
          .level(level)
          .attributes(attributes.isEmpty() ? null : attributes)
          .sort(code.getSort())
          .build();

      result.add(flatItem);

      // 하위 코드가 있는 경우 재귀 호출
      if (code.hasChildren() && code.getChildClassName() != null) {
        buildFlatStructure(code.getChildClassName(), currentPath, level + 1, result);
      }
    }
  }
}
