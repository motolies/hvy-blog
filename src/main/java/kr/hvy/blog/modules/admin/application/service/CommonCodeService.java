package kr.hvy.blog.modules.admin.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.hvy.blog.modules.admin.application.dto.*;
import kr.hvy.blog.modules.admin.domain.entity.CommonClass;
import kr.hvy.blog.modules.admin.domain.entity.CommonCode;
import kr.hvy.blog.modules.admin.domain.entity.CommonCodeId;
import kr.hvy.blog.modules.admin.mapper.CommonClassDtoMapper;
import kr.hvy.blog.modules.admin.mapper.CommonCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.CommonClassRepository;
import kr.hvy.blog.modules.admin.repository.CommonCodeRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 공통코드 관리 서비스
 * CRUD 작업 및 캐시 무효화 담당
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommonCodeService {

  @PersistenceContext
  private EntityManager entityManager;

  private final CommonClassRepository commonClassRepository;
  private final CommonCodeRepository commonCodeRepository;
  private final CommonClassDtoMapper commonClassDtoMapper;
  private final CommonCodeDtoMapper commonCodeDtoMapper;

  // ========== CommonClass 관련 메서드 ==========

  /**
   * 클래스 생성
   */
  @CacheEvict(cacheNames = "commonCodeClass", key = "'all'")
  public CommonClassResponse createClass(CommonClassCreate createDto) {
    // 중복 검사
    if (commonClassRepository.existsByCode(createDto.getCode())) {
      throw new IllegalArgumentException("이미 존재하는 클래스 코드입니다: " + createDto.getCode());
    }

    CommonClass entity = commonClassDtoMapper.toDomain(createDto);
    CommonClass savedEntity = commonClassRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonClass created: {}", savedEntity.getCode());
    return commonClassDtoMapper.toResponse(savedEntity);
  }

  /**
   * 클래스 수정
   * Surrogate Key 패턴: code 필드도 단순 update 가능
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeClass", key = "#classCode"),
      @CacheEvict(cacheNames = "commonCodeClass", key = "'all'")
  })
  public CommonClassResponse updateClass(String classCode, CommonClassUpdate updateDto) {
    CommonClass entity = findClassByCode(classCode);

    // code 변경 시 중복 검증
    if (updateDto.getCode() != null && !updateDto.getCode().trim().isEmpty()
        && !classCode.equals(updateDto.getCode())) {
      if (commonClassRepository.existsByCodeAndIsActiveTrue(updateDto.getCode())) {
        throw new IllegalArgumentException("이미 존재하는 클래스 코드입니다: " + updateDto.getCode());
      }
    }

    entity.update(updateDto);

    CommonClass savedEntity = commonClassRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonClass updated: {} -> {}", classCode, savedEntity.getCode());
    return commonClassDtoMapper.toResponse(savedEntity);
  }

  /**
   * 클래스 삭제
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeClass", key = "#classCode"),
      @CacheEvict(cacheNames = "commonCodeClass", key = "'all'"),
      @CacheEvict(cacheNames = "commonCodeData", key = "#classCode"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public DeleteResponse<Long> deleteClass(String classCode) {
    CommonClass entity = findClassByCode(classCode);

    // 관련 코드가 있는지 확인
    long codeCount = commonCodeRepository.countByCommonClass_CodeAndIsActiveTrue(classCode);
    if (codeCount > 0) {
      throw new IllegalArgumentException("클래스에 활성화된 코드가 존재하여 삭제할 수 없습니다. 코드 수: " + codeCount);
    }

    Long deletedId = entity.getId();
    commonClassRepository.delete(entity);
    log.info("CommonClass deleted: {} (ID: {})", classCode, deletedId);

    return DeleteResponse.<Long>builder()
        .id(deletedId)
        .build();
  }

  /**
   * 클래스 조회
   */
  public CommonClassResponse getClass(String classCode) {
    CommonClass entity = findClassByCode(classCode);
    return commonClassDtoMapper.toResponse(entity);
  }

  /**
   * 모든 클래스 조회
   */
  public List<CommonClassResponse> getAllClasses() {
    List<CommonClass> entities = commonClassRepository.findActiveClassesOrderByCode();
    return commonClassDtoMapper.toResponseList(entities);
  }

  // ========== CommonCode 관련 메서드 ==========

  /**
   * 코드 생성
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "#createDto.classCode"),
      @CacheEvict(cacheNames = "commonCodeTree", key = "#createDto.classCode"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public CommonCodeResponse createCode(CommonCodeCreate createDto) {
    // 클래스 존재 확인 및 조회
    CommonClass commonClass = findClassByCode(createDto.getClassCode());

    // 코드 중복 검사
    if (commonCodeRepository.existsByCommonClass_CodeAndCodeAndIsActiveTrue(createDto.getClassCode(), createDto.getCode())) {
      throw new IllegalArgumentException(
          String.format("이미 존재하는 코드입니다: %s.%s", createDto.getClassCode(), createDto.getCode()));
    }

    // 하위 클래스 존재 확인
    CommonClass childClass = null;
    if (createDto.getChildClassCode() != null && !createDto.getChildClassCode().trim().isEmpty()) {
      childClass = commonClassRepository.findByCodeAndIsActiveTrue(createDto.getChildClassCode())
          .orElseThrow(() -> new DataNotFoundException("하위 클래스를 찾을 수 없습니다: " + createDto.getChildClassCode()));
    }

    // 정렬 순서 자동 설정 (지정되지 않은 경우)
    Integer sort = createDto.getSort();
    if (sort == null || sort == 0) {
      Integer maxSort = commonCodeRepository.findMaxSortByClassCode(createDto.getClassCode());
      sort = maxSort + 1;
    }

    // 엔티티 생성 (관계 설정)
    CommonCode entity = CommonCode.builder()
        .code(createDto.getCode())
        .name(createDto.getName())
        .description(createDto.getDescription())
        .attribute1Value(createDto.getAttribute1Value())
        .attribute2Value(createDto.getAttribute2Value())
        .attribute3Value(createDto.getAttribute3Value())
        .attribute4Value(createDto.getAttribute4Value())
        .attribute5Value(createDto.getAttribute5Value())
        .commonClass(commonClass)  // FK 설정
        .childClass(childClass)     // FK 설정
        .sort(sort)
        .isActive(createDto.getIsActive() != null ? createDto.getIsActive() : true)
        .build();

    CommonCode savedEntity = commonCodeRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonCode created: {}.{}", commonClass.getCode(), savedEntity.getCode());
    return commonCodeDtoMapper.toResponse(savedEntity);
  }

  /**
   * 코드 수정
   * Surrogate Key 패턴: code 필드도 단순 update 가능
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "#classCode"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public CommonCodeResponse updateCode(String classCode, String code, CommonCodeUpdate updateDto) {
    CommonCode entity = findCodeByClassCodeAndCode(classCode, code);

    // code 변경 시 중복 검증
    if (updateDto.getCode() != null && !updateDto.getCode().trim().isEmpty()
        && !code.equals(updateDto.getCode())) {
      if (commonCodeRepository.existsByCommonClass_CodeAndCodeAndIsActiveTrue(classCode, updateDto.getCode())) {
        throw new IllegalArgumentException(
            String.format("이미 존재하는 코드입니다: %s.%s", classCode, updateDto.getCode()));
      }
      log.info("Code change: {}.{} -> {}.{}", classCode, code, classCode, updateDto.getCode());
    }

    // 하위 클래스 존재 확인 및 설정
    if (updateDto.getChildClassCode() != null && !updateDto.getChildClassCode().trim().isEmpty()) {
      CommonClass childClass = commonClassRepository.findByCodeAndIsActiveTrue(updateDto.getChildClassCode())
          .orElseThrow(() -> new DataNotFoundException("하위 클래스를 찾을 수 없습니다: " + updateDto.getChildClassCode()));
      entity.setChildClass(childClass);
    } else {
      entity.setChildClass(null);
    }

    // 엔티티 업데이트 (단순 update, delete + create 불필요)
    entity.update(updateDto);
    CommonCode savedEntity = commonCodeRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonCode updated: {}.{}", classCode, savedEntity.getCode());
    return commonCodeDtoMapper.toResponse(savedEntity);
  }

  /**
   * 코드 삭제
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "'single:'+#classCode+':'+#code"),
      @CacheEvict(cacheNames = "commonCodeData", key = "#classCode"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public DeleteResponse<Long> deleteCode(String classCode, String code) {
    CommonCode entity = findCodeByClassCodeAndCode(classCode, code);

    // 하위 코드 참조 확인
    if (entity.hasChildren() && entity.getChildClass() != null) {
      long childCount = commonCodeRepository.countByCommonClass_CodeAndIsActiveTrue(entity.getChildClass().getCode());
      if (childCount > 0) {
        throw new IllegalArgumentException("하위 코드가 존재하여 삭제할 수 없습니다. 하위 코드 수: " + childCount);
      }
    }

    Long deletedId = entity.getId();
    commonCodeRepository.delete(entity);
    log.info("CommonCode deleted: {}.{} (ID: {})", classCode, code, deletedId);

    return DeleteResponse.<Long>builder()
        .id(deletedId)
        .build();
  }

  /**
   * 코드 배치 생성
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "#classCode"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public List<CommonCodeResponse> batchCreateCodes(String classCode, List<CommonCodeCreate> createDtos) {
    // 클래스 존재 확인
    findClassByCode(classCode);

    return createDtos.stream()
        .map(dto -> {
          CommonCodeCreate classCodeFixedDto = CommonCodeCreate.builder()
              .classCode(classCode)
              .code(dto.getCode())
              .name(dto.getName())
              .description(dto.getDescription())
              .attribute1Value(dto.getAttribute1Value())
              .attribute2Value(dto.getAttribute2Value())
              .attribute3Value(dto.getAttribute3Value())
              .attribute4Value(dto.getAttribute4Value())
              .attribute5Value(dto.getAttribute5Value())

              .childClassCode(dto.getChildClassCode())
              .sort(dto.getSort())
              .isActive(dto.getIsActive())
              .build();
          return createCode(classCodeFixedDto);
        })
        .toList();
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

  // ========== 트리 구조 관련 메서드 ==========

  /**
   * 전체 공통코드 트리 구조 조회
   * CLASS → CODE → CLASS → CODE 재귀 구조
   */
  public List<CommonCodeTreeNodeResponse> getTreeStructure() {
    // 모든 활성 클래스 조회 (코드 포함)
    List<CommonClass> allClasses = commonClassRepository.findActiveClassesOrderByCode();

    // 하위 클래스로 참조되는 클래스 코드 수집
    Set<String> childClassCodes = collectChildClassCodes(allClasses);

    // 최상위 클래스 필터링 (다른 클래스의 childClassName으로 참조되지 않는 클래스)
    List<CommonClass> rootClasses = allClasses.stream()
        .filter(cls -> !childClassCodes.contains(cls.getCode()))
        .toList();

    // 순환 참조 방지를 위한 처리된 클래스 추적
    Set<String> processedClasses = new HashSet<>();

    // 재귀적으로 트리 구성
    return rootClasses.stream()
        .map(cls -> buildClassTreeNode(cls, allClasses, processedClasses))
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * 하위 클래스로 참조되는 클래스 코드 수집
   */
  private Set<String> collectChildClassCodes(List<CommonClass> classes) {
    Set<String> childClassCodes = new HashSet<>();
    for (CommonClass cls : classes) {
      if (cls.getCodes() != null) {
        for (CommonCode code : cls.getCodes()) {
          if (code.getChildClass() != null) {
            childClassCodes.add(code.getChildClass().getCode());
          }
        }
      }
    }
    return childClassCodes;
  }

  /**
   * CLASS 노드 생성 (재귀)
   * @param classData 클래스 엔티티
   * @param allClasses 전체 클래스 목록
   * @param processedClasses 처리된 클래스 추적 (순환 참조 방지)
   * @return CLASS 타입 트리 노드
   */
  private CommonCodeTreeNodeResponse buildClassTreeNode(
      CommonClass classData,
      List<CommonClass> allClasses,
      Set<String> processedClasses) {

    // 순환 참조 방지
    if (processedClasses.contains(classData.getCode())) {
      log.warn("순환 참조 감지: {}", classData.getCode());
      return null;
    }

    processedClasses.add(classData.getCode());

    // 클래스 정보를 응답 DTO로 변환
    CommonClassResponse classResponse = commonClassDtoMapper.toResponse(classData);

    // 코드 노드들 생성
    List<CommonCodeTreeNodeResponse> codeNodes = new ArrayList<>();
    if (classData.getCodes() != null && !classData.getCodes().isEmpty()) {
      // 정렬 순서 적용
      List<CommonCode> sortedCodes = classData.getCodes().stream()
          .sorted(Comparator.comparing(CommonCode::getSort, Comparator.nullsLast(Comparator.naturalOrder())))
          .toList();

      for (CommonCode code : sortedCodes) {
        // 새로운 Set을 생성하여 각 코드 브랜치에서 독립적인 추적
        CommonCodeTreeNodeResponse codeNode = buildCodeTreeNode(code, allClasses, new HashSet<>(processedClasses));
        if (codeNode != null) {
          codeNodes.add(codeNode);
        }
      }
    }

    // CLASS 노드 생성
    return CommonCodeTreeNodeResponse.fromClass(classResponse, codeNodes.isEmpty() ? null : codeNodes);
  }

  /**
   * CODE 노드 생성 (재귀)
   * @param codeData 코드 엔티티
   * @param allClasses 전체 클래스 목록
   * @param processedClasses 처리된 클래스 추적 (순환 참조 방지)
   * @return CODE 타입 트리 노드
   */
  private CommonCodeTreeNodeResponse buildCodeTreeNode(
      CommonCode codeData,
      List<CommonClass> allClasses,
      Set<String> processedClasses) {

    // 코드 정보를 응답 DTO로 변환
    CommonCodeResponse codeResponse = commonCodeDtoMapper.toResponse(codeData);

    // 하위 클래스가 있으면 재귀적으로 CLASS 노드 생성
    CommonCodeTreeNodeResponse childClassNode = null;
    if (codeData.hasChildren()) {
      CommonClass childClass = allClasses.stream()
          .filter(cls -> cls.getCode().equals(codeData.getChildClass().getCode()))
          .findFirst()
          .orElse(null);

      if (childClass != null) {
        childClassNode = buildClassTreeNode(childClass, allClasses, processedClasses);
      } else {
        log.warn("하위 클래스를 찾을 수 없음: {}", codeData.getChildClass().getCode());
      }
    }

    // CODE 노드 생성
    return CommonCodeTreeNodeResponse.fromCode(codeResponse, childClassNode);
  }
}
