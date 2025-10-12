package kr.hvy.blog.modules.admin.application;

import kr.hvy.blog.modules.admin.application.dto.*;
import kr.hvy.blog.modules.admin.application.service.CommonCodeService;
import kr.hvy.blog.modules.admin.repository.CommonCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
  public ResponseEntity<?> createClass(@Valid @RequestBody CommonClassCreate createDto) {
    log.info("Creating CommonClass: {}", createDto.getCode());
    CommonClassResponse response = commonCodeService.createClass(createDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 클래스 수정
   */
  @PutMapping("/class/{classCode}")
  public ResponseEntity<?> updateClass(
      @PathVariable String classCode,
      @Valid @RequestBody CommonClassUpdate updateDto) {
    log.info("Updating CommonClass: {}", classCode);
    CommonClassResponse response = commonCodeService.updateClass(classCode, updateDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 클래스 삭제
   */
  @DeleteMapping("/class/{classCode}")
  public ResponseEntity<?> deleteClass(@PathVariable String classCode) {
    log.info("Deleting CommonClass: {}", classCode);
    return ResponseEntity.ok(commonCodeService.deleteClass(classCode));
  }

  /**
   * 클래스 상세 조회 (관리자용)
   */
  @GetMapping("/class/{classCode}")
  public ResponseEntity<?> getClass(@PathVariable String classCode) {
    CommonClassResponse response = commonCodeService.getClass(classCode);
    return ResponseEntity.ok(response);
  }

  /**
   * 모든 클래스 조회 (관리자용)
   */
  @GetMapping("/class")
  public ResponseEntity<?> getAllClasses() {
    List<CommonClassResponse> response = commonCodeService.getAllClasses();
    return ResponseEntity.ok(response);
  }

  // ========== CommonCode 관리 API ==========

  /**
   * 코드 생성
   */
  @PostMapping("/code")
  public ResponseEntity<?> createCode(@Valid @RequestBody CommonCodeCreate createDto) {
    log.info("Creating CommonCode: {}.{}", createDto.getClassCode(), createDto.getCode());
    CommonCodeResponse response = commonCodeService.createCode(createDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 코드 수정
   */
  @PutMapping("/code/{classCode}/{code}")
  public ResponseEntity<?> updateCode(
      @PathVariable String classCode,
      @PathVariable String code,
      @Valid @RequestBody CommonCodeUpdate updateDto) {
    log.info("Updating CommonCode: {}.{}", classCode, code);
    CommonCodeResponse response = commonCodeService.updateCode(classCode, code, updateDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 코드 삭제
   */
  @DeleteMapping("/code/{classCode}/{code}")
  public ResponseEntity<?> deleteCode(
      @PathVariable String classCode,
      @PathVariable String code) {
    log.info("Deleting CommonCode: {}.{}", classCode, code);
    return ResponseEntity.ok(commonCodeService.deleteCode(classCode, code));
  }

  /**
   * 배치 코드 생성
   */
  @PostMapping("/class/{classCode}/codes/batch")
  public ResponseEntity<?> batchCreateCodes(
      @PathVariable String classCode,
      @Valid @RequestBody List<CommonCodeCreate> createDtos) {
    log.info("Batch creating CommonCodes for class: {}, count: {}", classCode, createDtos.size());
    List<CommonCodeResponse> response = commonCodeService.batchCreateCodes(classCode, createDtos);
    return ResponseEntity.ok(response);
  }

  // ========== 유틸리티 API ==========

  /**
   * 클래스 코드 중복 확인
   */
  @GetMapping("/class/{classCode}/exists")
  public ResponseEntity<?> checkClassExists(@PathVariable String classCode) {
    try {
      commonCodeService.getClass(classCode);
      return ResponseEntity.ok(Map.of("exists", true));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of("exists", false));
    }
  }

  /**
   * 클래스 내 코드명 중복 확인
   */
  @GetMapping("/code/{classCode}/{code}/exists")
  public ResponseEntity<?> checkCodeExists(
      @PathVariable String classCode,
      @PathVariable String code) {
    Map<String, Boolean> result = Map.of("exists",
        commonCodeRepository.existsByCommonClass_CodeAndCode(classCode, code));
    return ResponseEntity.ok(result);
  }

  /**
   * 공통코드 트리 구조 조회
   * CLASS → CODE → CLASS → CODE 재귀 구조
   */
  @GetMapping("/tree")
  public ResponseEntity<?> getTreeStructure() {
    log.info("Fetching common code tree structure");
    List<CommonCodeTreeNodeResponse> treeStructure = commonCodeService.getTreeStructure();
    return ResponseEntity.ok(treeStructure);
  }
}
