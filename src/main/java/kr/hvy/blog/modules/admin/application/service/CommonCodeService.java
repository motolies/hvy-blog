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

import java.util.List;

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
    if (commonClassRepository.existsByName(createDto.getName())) {
      throw new IllegalArgumentException("이미 존재하는 클래스명입니다: " + createDto.getName());
    }

    CommonClass entity = commonClassDtoMapper.toDomain(createDto);
    CommonClass savedEntity = commonClassRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonClass created: {}", savedEntity.getName());
    return commonClassDtoMapper.toResponse(savedEntity);
  }

  /**
   * 클래스 수정
   */
  @CachePut(cacheNames = "commonCodeClass", key = "#className")
  @CacheEvict(cacheNames = "commonCodeClass", key = "'all'")
  public CommonClassResponse updateClass(String className, CommonClassUpdate updateDto) {
    CommonClass entity = findClassByName(className);
    entity.update(updateDto);

    CommonClass savedEntity = commonClassRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonClass updated: {}", savedEntity.getName());
    return commonClassDtoMapper.toResponse(savedEntity);
  }

  /**
   * 클래스 삭제
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeClass", key = "#className"),
      @CacheEvict(cacheNames = "commonCodeClass", key = "'all'"),
      @CacheEvict(cacheNames = "commonCodeData", key = "#className"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public DeleteResponse<String> deleteClass(String className) {
    CommonClass entity = findClassByName(className);

    // 관련 코드가 있는지 확인
    long codeCount = commonCodeRepository.countByClassNameAndIsActiveTrue(className);
    if (codeCount > 0) {
      throw new IllegalArgumentException("클래스에 활성화된 코드가 존재하여 삭제할 수 없습니다. 코드 수: " + codeCount);
    }

    commonClassRepository.delete(entity);
    log.info("CommonClass deleted: {}", className);

    return DeleteResponse.<String>builder()
        .id(className)
        .build();
  }

  /**
   * 클래스 조회
   */
  public CommonClassResponse getClass(String className) {
    CommonClass entity = findClassByName(className);
    return commonClassDtoMapper.toResponse(entity);
  }

  /**
   * 모든 클래스 조회
   */
  public List<CommonClassResponse> getAllClasses() {
    List<CommonClass> entities = commonClassRepository.findActiveClassesOrderByName();
    return commonClassDtoMapper.toResponseList(entities);
  }

  // ========== CommonCode 관련 메서드 ==========

  /**
   * 코드 생성
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "#createDto.className"),
      @CacheEvict(cacheNames = "commonCodeTree", key = "#createDto.className"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public CommonCodeResponse createCode(CommonCodeCreate createDto) {
    // 클래스 존재 확인
    findClassByName(createDto.getClassName());

    // 코드 중복 검사
    if (commonCodeRepository.existsByClassNameAndCodeAndIsActiveTrue(createDto.getClassName(), createDto.getCode())) {
      throw new IllegalArgumentException(
          String.format("이미 존재하는 코드입니다: %s.%s", createDto.getClassName(), createDto.getCode()));
    }

    // 하위 클래스 존재 확인 (hasChildren=true인 경우)
    if (createDto.getChildClassName() != null && !createDto.getChildClassName().trim().isEmpty()) {
      if (!commonClassRepository.existsByNameAndIsActiveTrue(createDto.getChildClassName())) {
        throw new DataNotFoundException("하위 클래스를 찾을 수 없습니다: " + createDto.getChildClassName());
      }
    }

    // 정렬 순서 자동 설정 (지정되지 않은 경우)
    CommonCodeCreate finalCreateDto = createDto;
    if (createDto.getSort() == null || createDto.getSort() == 0) {
      Integer maxSort = commonCodeRepository.findMaxSortByClassName(createDto.getClassName());
      finalCreateDto = CommonCodeCreate.builder()
          .className(createDto.getClassName())
          .code(createDto.getCode())
          .name(createDto.getName())
          .description(createDto.getDescription())
          .attribute1Value(createDto.getAttribute1Value())
          .attribute2Value(createDto.getAttribute2Value())
          .attribute3Value(createDto.getAttribute3Value())
          .attribute4Value(createDto.getAttribute4Value())
          .attribute5Value(createDto.getAttribute5Value())

          .childClassName(createDto.getChildClassName())
          .sort(maxSort + 1)
          .isActive(createDto.getIsActive())
          .build();
    }

    CommonCode entity = commonCodeDtoMapper.toDomain(finalCreateDto);
    CommonCode savedEntity = commonCodeRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonCode created: {}.{}", savedEntity.getClassName(), savedEntity.getCode());
    return commonCodeDtoMapper.toResponse(savedEntity);
  }

  /**
   * 코드 수정
   */
  @CachePut(cacheNames = "commonCodeData", key = "'single:'+#className+':'+#code")
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "#className"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public CommonCodeResponse updateCode(String className, String code, CommonCodeUpdate updateDto) {
    CommonCode entity = findCodeByClassNameAndCode(className, code);

    // 하위 클래스 존재 확인 (hasChildren=true이고 childClassName이 변경된 경우)
    if (updateDto.getChildClassName() != null && !updateDto.getChildClassName().trim().isEmpty()) {
      if (!commonClassRepository.existsByNameAndIsActiveTrue(updateDto.getChildClassName())) {
        throw new DataNotFoundException("하위 클래스를 찾을 수 없습니다: " + updateDto.getChildClassName());
      }
    }

    entity.update(updateDto);
    CommonCode savedEntity = commonCodeRepository.save(entity);
    entityManager.flush();
    entityManager.refresh(savedEntity);

    log.info("CommonCode updated: {}.{}", savedEntity.getClassName(), savedEntity.getCode());
    return commonCodeDtoMapper.toResponse(savedEntity);
  }

  /**
   * 코드 삭제
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "'single:'+#className+':'+#code"),
      @CacheEvict(cacheNames = "commonCodeData", key = "#className"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public DeleteResponse<CommonCodeId> deleteCode(String className, String code) {
    CommonCode entity = findCodeByClassNameAndCode(className, code);

    // 하위 코드 참조 확인
    if (entity.hasChildren() && entity.getChildClassName() != null) {
      long childCount = commonCodeRepository.countByClassNameAndIsActiveTrue(entity.getChildClassName());
      if (childCount > 0) {
        throw new IllegalArgumentException("하위 코드가 존재하여 삭제할 수 없습니다. 하위 코드 수: " + childCount);
      }
    }

    commonCodeRepository.delete(entity);
    log.info("CommonCode deleted: {}.{}", className, code);

    return DeleteResponse.<CommonCodeId>builder()
        .id(new CommonCodeId(className, code))
        .build();
  }

  /**
   * 코드 배치 생성
   */
  @Caching(evict = {
      @CacheEvict(cacheNames = "commonCodeData", key = "#className"),
      @CacheEvict(cacheNames = "commonCodeTree", allEntries = true)
  })
  public List<CommonCodeResponse> batchCreateCodes(String className, List<CommonCodeCreate> createDtos) {
    // 클래스 존재 확인
    findClassByName(className);

    return createDtos.stream()
        .map(dto -> {
          CommonCodeCreate classNameFixedDto = CommonCodeCreate.builder()
              .className(className)
              .code(dto.getCode())
              .name(dto.getName())
              .description(dto.getDescription())
              .attribute1Value(dto.getAttribute1Value())
              .attribute2Value(dto.getAttribute2Value())
              .attribute3Value(dto.getAttribute3Value())
              .attribute4Value(dto.getAttribute4Value())
              .attribute5Value(dto.getAttribute5Value())

              .childClassName(dto.getChildClassName())
              .sort(dto.getSort())
              .isActive(dto.getIsActive())
              .build();
          return createCode(classNameFixedDto);
        })
        .toList();
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
}
