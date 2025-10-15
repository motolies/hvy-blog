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
 * 공통코드 공개 조회 서비스 읽기 전용 작업 및 캐시 적용 담당
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
    List<CommonClass> entities = commonClassRepository.findActiveClassesOrderByCode();
    return commonClassDtoMapper.toResponseList(entities);
  }

  /**
   * 특정 클래스 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeClass", key = "#classCode")
  public CommonClassResponse getClass(String classCode) {
    CommonClass entity = findClassByCode(classCode);
    return commonClassDtoMapper.toResponse(entity);
  }

  // ========== 코드 조회 ==========

  /**
   * 클래스별 코드 목록 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeData", key = "#classCode")
  public List<CommonCodeResponse> getCodesByClass(String classCode) {
    // 클래스 존재 확인
    findClassByCode(classCode);

    List<CommonCode> entities = commonCodeRepository.findByCommonClass_CodeAndIsActiveTrueOrderBySortAscCodeAsc(classCode);
    return commonCodeDtoMapper.toResponseList(entities);
  }

  /**
   * 특정 코드 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeData", key = "'single:'+#classCode+':'+#code")
  public CommonCodeResponse getCode(String classCode, String code) {
    CommonCode entity = findCodeByClassCodeAndCode(classCode, code);
    return commonCodeDtoMapper.toResponse(entity);
  }

  /**
   * 하위 코드 조회
   */
  public List<CommonCodeResponse> getChildCodes(String parentClassCode, String parentCode) {
    CommonCode parentCodeEntity = findCodeByClassCodeAndCode(parentClassCode, parentCode);

    if (!parentCodeEntity.hasChildren()) {
      return new ArrayList<>();
    }

    List<CommonCode> childEntities = commonCodeRepository.findChildCodes(parentClassCode, parentCode);
    return commonCodeDtoMapper.toResponseList(childEntities);
  }

  // ========== 트리 구조 조회 ==========

  /**
   * 계층 구조 트리 조회 (캐시됨)
   */
  @Cacheable(cacheNames = "commonCodeTree", key = "#classCode")
  public CommonCodeTreeResponse getCodesWithTree(String classCode) {
    CommonClass classEntity = findClassByCode(classCode);
    List<CommonCode> rootCodes = commonCodeRepository.findByCommonClass_CodeAndIsActiveTrueOrderBySortAscCodeAsc(classCode);

    List<CommonCodeTreeResponse.CommonCodeItemResponse> treeItems = buildTreeStructure(rootCodes);

    return CommonCodeTreeResponse.builder()
        .className(classEntity.getName())
        .codes(treeItems)
        .totalCount(treeItems.size())
        .build();
  }

  /**
   * 평면 구조 조회 (select box용)
   */
  public List<CommonCodeTreeResponse.CommonCodeFlatResponse> getFlatCodes(String classCode) {
    List<CommonCodeTreeResponse.CommonCodeFlatResponse> result = new ArrayList<>();
    buildFlatStructure(classCode, "", 0, result);
    return result;
  }

  // ========== 검색 ==========

  /**
   * 클래스 내 코드명 검색
   */
  public List<CommonCodeResponse> searchCodesInClass(String classCode, String searchTerm) {
    // 클래스 존재 확인
    findClassByCode(classCode);

    List<CommonCode> entities = commonCodeRepository.searchByNameInClass(classCode, searchTerm);
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
  public List<CommonCodeResponse> searchCodesByAttribute(String classCode, String attributeValue) {
    // 클래스 존재 확인
    findClassByCode(classCode);

    List<CommonCode> entities = commonCodeRepository.findByAttributeValue(classCode, attributeValue);
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
            result -> (String) result[0],  // classCode
            result -> (Long) result[1]     // count
        ));
  }

  // ========== 헬퍼 메서드 ==========

  private CommonClass findClassByCode(String classCode) {
    return commonClassRepository.findByCodeAndIsActiveTrue(classCode)
        .orElseThrow(() -> new DataNotFoundException("클래스를 찾을 수 없습니다: " + classCode));
  }

  private CommonCode findCodeByClassCodeAndCode(String classCode, String code) {
    return commonCodeRepository.findByCommonClass_CodeAndCodeAndIsActiveTrue(classCode, code)
        .orElseThrow(() -> new DataNotFoundException(
            String.format("코드를 찾을 수 없습니다: %s.%s", classCode, code)));
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

              .childClassCode(code.hasChildren() ? code.getChildClass().getCode() : null)
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
      if (code.hasChildren()) {
        List<CommonCode> childCodes = commonCodeRepository.findByCommonClass_CodeAndIsActiveTrueOrderBySortAscCodeAsc(code.getChildClass().getCode());
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
  private void buildFlatStructure(String classCode, String pathPrefix, int level,
      List<CommonCodeTreeResponse.CommonCodeFlatResponse> result) {
    List<CommonCode> codes = commonCodeRepository.findByCommonClass_CodeAndIsActiveTrueOrderBySortAscCodeAsc(classCode);

    for (CommonCode code : codes) {
      String currentPath = pathPrefix.isEmpty() ? code.getName() : pathPrefix + " > " + code.getName();

      // 동적 속성 맵 생성
      Map<String, String> attributes = new HashMap<>();
      if (code.getAttribute1Value() != null) {
        attributes.put("attribute1", code.getAttribute1Value());
      }
      if (code.getAttribute2Value() != null) {
        attributes.put("attribute2", code.getAttribute2Value());
      }
      if (code.getAttribute3Value() != null) {
        attributes.put("attribute3", code.getAttribute3Value());
      }
      if (code.getAttribute4Value() != null) {
        attributes.put("attribute4", code.getAttribute4Value());
      }
      if (code.getAttribute5Value() != null) {
        attributes.put("attribute5", code.getAttribute5Value());
      }

      CommonCodeTreeResponse.CommonCodeFlatResponse flatItem = CommonCodeTreeResponse.CommonCodeFlatResponse.builder()
          .fullPath(currentPath)
          .classCode(code.getCommonClass().getCode())
          .code(code.getCode())
          .name(code.getName())
          .level(level)
          .attributes(attributes.isEmpty() ? null : attributes)
          .sort(code.getSort())
          .build();

      result.add(flatItem);

      // 하위 코드가 있는 경우 재귀 호출
      if (code.hasChildren()) {
        buildFlatStructure(code.getChildClass().getCode(), currentPath, level + 1, result);
      }
    }
  }
}
