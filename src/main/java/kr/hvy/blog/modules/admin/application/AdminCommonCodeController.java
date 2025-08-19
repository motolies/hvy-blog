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
    log.info("Creating CommonClass: {}", createDto.getName());
    CommonClassResponse response = commonCodeService.createClass(createDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 클래스 수정
   */
  @PutMapping("/class/{className}")
  public ResponseEntity<?> updateClass(
      @PathVariable String className,
      @Valid @RequestBody CommonClassUpdate updateDto) {
    log.info("Updating CommonClass: {}", className);
    CommonClassResponse response = commonCodeService.updateClass(className, updateDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 클래스 삭제
   */
  @DeleteMapping("/class/{className}")
  public ResponseEntity<?> deleteClass(@PathVariable String className) {
    log.info("Deleting CommonClass: {}", className);
    return ResponseEntity.ok(commonCodeService.deleteClass(className));
  }

  /**
   * 클래스 상세 조회 (관리자용)
   */
  @GetMapping("/class/{className}")
  public ResponseEntity<?> getClass(@PathVariable String className) {
    CommonClassResponse response = commonCodeService.getClass(className);
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
    log.info("Creating CommonCode: {}.{}", createDto.getClassName(), createDto.getCode());
    CommonCodeResponse response = commonCodeService.createCode(createDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 코드 수정
   */
  @PutMapping("/code/{className}/{code}")
  public ResponseEntity<?> updateCode(
      @PathVariable String className,
      @PathVariable String code,
      @Valid @RequestBody CommonCodeUpdate updateDto) {
    log.info("Updating CommonCode: {}.{}", className, code);
    CommonCodeResponse response = commonCodeService.updateCode(className, code, updateDto);
    return ResponseEntity.ok(response);
  }

  /**
   * 코드 삭제
   */
  @DeleteMapping("/code/{className}/{code}")
  public ResponseEntity<?> deleteCode(
      @PathVariable String className,
      @PathVariable String code) {
    log.info("Deleting CommonCode: {}.{}", className, code);
    return ResponseEntity.ok(commonCodeService.deleteCode(className, code));
  }

  /**
   * 배치 코드 생성
   */
  @PostMapping("/class/{className}/codes/batch")
  public ResponseEntity<?> batchCreateCodes(
      @PathVariable String className,
      @Valid @RequestBody List<CommonCodeCreate> createDtos) {
    log.info("Batch creating CommonCodes for class: {}, count: {}", className, createDtos.size());
    List<CommonCodeResponse> response = commonCodeService.batchCreateCodes(className, createDtos);
    return ResponseEntity.ok(response);
  }

  // ========== 유틸리티 API ==========

  /**
   * 클래스명 중복 확인
   */
  @GetMapping("/class/{className}/exists")
  public ResponseEntity<?> checkClassExists(@PathVariable String className) {
    try {
      commonCodeService.getClass(className);
      return ResponseEntity.ok(Map.of("exists", true));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of("exists", false));
    }
  }

  /**
   * 클래스 내 코드명 중복 확인
   */
  @GetMapping("/code/{className}/{code}/exists")
  public ResponseEntity<?> checkCodeExists(
      @PathVariable String className,
      @PathVariable String code) {
    Map<String, Boolean> result = Map.of("exists",
        commonCodeRepository.existsByClassNameAndCodeAndIsActiveTrue(className, code));
    return ResponseEntity.ok(result);
  }
}
