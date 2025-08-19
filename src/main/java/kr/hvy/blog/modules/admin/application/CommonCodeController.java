package kr.hvy.blog.modules.admin.application;

import kr.hvy.blog.modules.admin.application.dto.*;
import kr.hvy.blog.modules.admin.application.service.CommonCodePublicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 공통코드 공개용 Controller
 * 조회 API 제공 (캐시 적용)
 */
@Slf4j
@RestController
@RequestMapping("/api/common-code")
@RequiredArgsConstructor
public class CommonCodeController {

  private final CommonCodePublicService commonCodePublicService;

  // ========== 클래스 조회 API ==========

  /**
   * 모든 활성화된 클래스 조회
   */
  @GetMapping("/class")
  public ResponseEntity<?> getAllClasses() {
    List<CommonClassResponse> response = commonCodePublicService.getAllClasses();
    return ResponseEntity.ok(response);
  }

  /**
   * 특정 클래스 조회
   */
  @GetMapping("/class/{className}")
  public ResponseEntity<?> getClass(@PathVariable String className) {
    CommonClassResponse response = commonCodePublicService.getClass(className);
    return ResponseEntity.ok(response);
  }

  // ========== 코드 조회 API ==========

  /**
   * 클래스별 코드 목록 조회
   */
  @GetMapping("/class/{className}/codes")
  public ResponseEntity<?> getCodesByClass(@PathVariable String className) {
    List<CommonCodeResponse> response = commonCodePublicService.getCodesByClass(className);
    return ResponseEntity.ok(response);
  }

  /**
   * 특정 코드 조회
   */
  @GetMapping("/code/{className}/{code}")
  public ResponseEntity<?> getCode(
      @PathVariable String className,
      @PathVariable String code) {
    CommonCodeResponse response = commonCodePublicService.getCode(className, code);
    return ResponseEntity.ok(response);
  }

  /**
   * 하위 코드 조회
   */
  @GetMapping("/code/{className}/{code}/children")
  public ResponseEntity<?> getChildCodes(
      @PathVariable String className,
      @PathVariable String code) {
    List<CommonCodeResponse> response = commonCodePublicService.getChildCodes(className, code);
    return ResponseEntity.ok(response);
  }

  // ========== 트리 구조 조회 API ==========

  /**
   * 계층 구조 트리 조회
   */
  @GetMapping("/class/{className}/tree")
  public ResponseEntity<?> getCodesWithTree(@PathVariable String className) {
    CommonCodeTreeResponse response = commonCodePublicService.getCodesWithTree(className);
    return ResponseEntity.ok(response);
  }

  /**
   * 평면 구조 조회 (select box용)
   */
  @GetMapping("/class/{className}/flat")
  public ResponseEntity<?> getFlatCodes(@PathVariable String className) {
    List<CommonCodeTreeResponse.CommonCodeFlatResponse> response =
        commonCodePublicService.getFlatCodes(className);
    return ResponseEntity.ok(response);
  }

  // ========== 검색 API ==========

  /**
   * 클래스 내 코드명 검색
   */
  @GetMapping("/class/{className}/search")
  public ResponseEntity<?> searchCodesInClass(
      @PathVariable String className,
      @RequestParam String q) {
    List<CommonCodeResponse> response = commonCodePublicService.searchCodesInClass(className, q);
    return ResponseEntity.ok(response);
  }

  /**
   * 전체 클래스에서 코드명 검색
   */
  @GetMapping("/search")
  public ResponseEntity<?> searchCodesAcrossAllClasses(@RequestParam String q) {
    List<CommonCodeResponse> response = commonCodePublicService.searchCodesAcrossAllClasses(q);
    return ResponseEntity.ok(response);
  }

  /**
   * 속성값으로 코드 검색
   */
  @GetMapping("/class/{className}/search-by-attribute")
  public ResponseEntity<?> searchCodesByAttribute(
      @PathVariable String className,
      @RequestParam String attributeValue) {
    List<CommonCodeResponse> response =
        commonCodePublicService.searchCodesByAttribute(className, attributeValue);
    return ResponseEntity.ok(response);
  }

  // ========== 통계 API ==========

  /**
   * 클래스별 코드 개수 통계
   */
  @GetMapping("/statistics/count-by-class")
  public ResponseEntity<?> getCodeCountByClass() {
    Map<String, Long> response = commonCodePublicService.getCodeCountByClass();
    return ResponseEntity.ok(response);
  }

  // ========== 편의 API ==========

  /**
   * 여러 클래스의 코드를 한번에 조회
   */
  @PostMapping("/codes/batch")
  public ResponseEntity<?> getBatchCodes(@RequestBody List<String> classNames) {
    Map<String, List<CommonCodeResponse>> result = new HashMap<>();

    for (String className : classNames) {
      try {
        List<CommonCodeResponse> codes = commonCodePublicService.getCodesByClass(className);
        result.put(className, codes);
      } catch (Exception e) {
        log.warn("Failed to get codes for class: {}", className, e);
        result.put(className, List.of());
      }
    }

    return ResponseEntity.ok(result);
  }

  /**
   * 코드 값 유효성 검증
   */
  @GetMapping("/validate/{className}/{code}")
  public ResponseEntity<?> validateCode(
      @PathVariable String className,
      @PathVariable String code) {
    try {
      CommonCodeResponse response = commonCodePublicService.getCode(className, code);
      return ResponseEntity.ok(Map.of(
          "valid", true,
          "code", response
      ));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of(
          "valid", false,
          "message", "유효하지 않은 코드입니다: " + className + "." + code
      ));
    }
  }
}
