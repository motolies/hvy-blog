package kr.hvy.blog.modules.admin.application;

import kr.hvy.blog.modules.admin.application.dto.*;
import kr.hvy.blog.modules.admin.application.service.CommonCodeService;
import kr.hvy.blog.modules.admin.repository.CommonCodeRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 공통코드 관리자용 Controller
 * 생성, 수정, 삭제 API 제공
 */
@Slf4j
@RestController
@RequestMapping("/api/common-code/admin")
@RequiredArgsConstructor
public class AdminCommonCodeController {

  private final CommonCodeService commonCodeService;
  private final CommonCodeRepository commonCodeRepository;

  // ========== CommonClass 관리 API ==========

  /**
   * 클래스 생성
   */
  @PostMapping("/class")
  @ResponseStatus(HttpStatus.CREATED)
  public CommonClassResponse createClass(@Valid @RequestBody CommonClassCreate createDto) {
    log.info("Creating CommonClass: {}", createDto.getCode());
    return commonCodeService.createClass(createDto);
  }

  /**
   * 클래스 수정
   */
  @PutMapping("/class/{classCode}")
  public CommonClassResponse updateClass(
      @PathVariable String classCode,
      @Valid @RequestBody CommonClassUpdate updateDto) {
    log.info("Updating CommonClass: {}", classCode);
    return commonCodeService.updateClass(classCode, updateDto);
  }

  /**
   * 클래스 삭제
   */
  @DeleteMapping("/class/{classCode}")
  public DeleteResponse<Long> deleteClass(@PathVariable String classCode) {
    log.info("Deleting CommonClass: {}", classCode);
    return commonCodeService.deleteClass(classCode);
  }

  /**
   * 클래스 상세 조회 (관리자용)
   */
  @GetMapping("/class/{classCode}")
  public CommonClassResponse getClass(@PathVariable String classCode) {
    return commonCodeService.getClass(classCode);
  }

  /**
   * 모든 클래스 조회 (관리자용)
   */
  @GetMapping("/class")
  public List<CommonClassResponse> getAllClasses() {
    return commonCodeService.getAllClasses();
  }

  // ========== CommonCode 관리 API ==========

  /**
   * 코드 생성
   */
  @PostMapping("/code")
  @ResponseStatus(HttpStatus.CREATED)
  public CommonCodeResponse createCode(@Valid @RequestBody CommonCodeCreate createDto) {
    log.info("Creating CommonCode: {}.{}", createDto.getClassCode(), createDto.getCode());
    return commonCodeService.createCode(createDto);
  }

  /**
   * 코드 수정
   */
  @PutMapping("/code/{classCode}/{code}")
  public CommonCodeResponse updateCode(
      @PathVariable String classCode,
      @PathVariable String code,
      @Valid @RequestBody CommonCodeUpdate updateDto) {
    log.info("Updating CommonCode: {}.{}", classCode, code);
    return commonCodeService.updateCode(classCode, code, updateDto);
  }

  /**
   * 코드 삭제
   */
  @DeleteMapping("/code/{classCode}/{code}")
  public DeleteResponse<Long> deleteCode(
      @PathVariable String classCode,
      @PathVariable String code) {
    log.info("Deleting CommonCode: {}.{}", classCode, code);
    return commonCodeService.deleteCode(classCode, code);
  }

  /**
   * 배치 코드 생성
   */
  @PostMapping("/class/{classCode}/codes/batch")
  @ResponseStatus(HttpStatus.CREATED)
  public List<CommonCodeResponse> batchCreateCodes(
      @PathVariable String classCode,
      @Valid @RequestBody List<CommonCodeCreate> createDtos) {
    log.info("Batch creating CommonCodes for class: {}, count: {}", classCode, createDtos.size());
    return commonCodeService.batchCreateCodes(classCode, createDtos);
  }

  // ========== 유틸리티 API ==========

  /**
   * 클래스 코드 중복 확인
   */
  @GetMapping("/class/{classCode}/exists")
  public Map<String, Boolean> checkClassExists(@PathVariable String classCode) {
    try {
      commonCodeService.getClass(classCode);
      return Map.of("exists", true);
    } catch (Exception e) {
      return Map.of("exists", false);
    }
  }

  /**
   * 클래스 내 코드명 중복 확인
   */
  @GetMapping("/code/{classCode}/{code}/exists")
  public Map<String, Boolean> checkCodeExists(
      @PathVariable String classCode,
      @PathVariable String code) {
    return Map.of("exists",
        commonCodeRepository.existsByCommonClass_CodeAndCode(classCode, code));
  }

  /**
   * 공통코드 트리 구조 조회
   * CLASS → CODE → CLASS → CODE 재귀 구조
   */
  @GetMapping("/tree")
  public List<CommonCodeTreeNodeResponse> getTreeStructure() {
    log.info("Fetching common code tree structure");
    return commonCodeService.getTreeStructure();
  }
}
