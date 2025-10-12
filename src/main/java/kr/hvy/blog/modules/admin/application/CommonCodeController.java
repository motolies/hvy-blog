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
  @GetMapping("/class/{classCode}")
  public ResponseEntity<?> getClass(@PathVariable String classCode) {
    CommonClassResponse response = commonCodePublicService.getClass(classCode);
    return ResponseEntity.ok(response);
  }

  // ========== 코드 조회 API ==========

  /**
   * 클래스별 코드 목록 조회
   */
  @GetMapping("/class/{classCode}/codes")
  public ResponseEntity<?> getCodesByClass(@PathVariable String classCode) {
    List<CommonCodeResponse> response = commonCodePublicService.getCodesByClass(classCode);
    return ResponseEntity.ok(response);
  }

  /**
   * 특정 코드 조회
   */
  @GetMapping("/code/{classCode}/{code}")
  public ResponseEntity<?> getCode(
      @PathVariable String classCode,
      @PathVariable String code) {
    CommonCodeResponse response = commonCodePublicService.getCode(classCode, code);
    return ResponseEntity.ok(response);
  }

  /**
   * 하위 코드 조회
   */
  @GetMapping("/code/{classCode}/{code}/children")
  public ResponseEntity<?> getChildCodes(
      @PathVariable String classCode,
      @PathVariable String code) {
    List<CommonCodeResponse> response = commonCodePublicService.getChildCodes(classCode, code);
    return ResponseEntity.ok(response);
  }

  // ========== 트리 구조 조회 API ==========

  /**
   * 계층 구조 트리 조회
   */
  @GetMapping("/class/{classCode}/tree")
  public ResponseEntity<?> getCodesWithTree(@PathVariable String classCode) {
    CommonCodeTreeResponse response = commonCodePublicService.getCodesWithTree(classCode);
    return ResponseEntity.ok(response);
  }

  /**
   * 평면 구조 조회 (select box용)
   */
  @GetMapping("/class/{classCode}/flat")
  public ResponseEntity<?> getFlatCodes(@PathVariable String classCode) {
    List<CommonCodeTreeResponse.CommonCodeFlatResponse> response =
        commonCodePublicService.getFlatCodes(classCode);
    return ResponseEntity.ok(response);
  }

  // ========== 검색 API ==========

  /**
   * 클래스 내 코드명 검색
   */
  @GetMapping("/class/{classCode}/search")
  public ResponseEntity<?> searchCodesInClass(
      @PathVariable String classCode,
      @RequestParam String q) {
    List<CommonCodeResponse> response = commonCodePublicService.searchCodesInClass(classCode, q);
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
  @GetMapping("/class/{classCode}/search-by-attribute")
  public ResponseEntity<?> searchCodesByAttribute(
      @PathVariable String classCode,
      @RequestParam String attributeValue) {
    List<CommonCodeResponse> response =
        commonCodePublicService.searchCodesByAttribute(classCode, attributeValue);
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
  public ResponseEntity<?> getBatchCodes(@RequestBody List<String> classCodes) {
    Map<String, List<CommonCodeResponse>> result = new HashMap<>();

    for (String classCode : classCodes) {
      try {
        List<CommonCodeResponse> codes = commonCodePublicService.getCodesByClass(classCode);
        result.put(classCode, codes);
      } catch (Exception e) {
        log.warn("Failed to get codes for class: {}", classCode, e);
        result.put(classCode, List.of());
      }
    }

    return ResponseEntity.ok(result);
  }

  /**
   * 코드 값 유효성 검증
   */
  @GetMapping("/validate/{classCode}/{code}")
  public ResponseEntity<?> validateCode(
      @PathVariable String classCode,
      @PathVariable String code) {
    try {
      CommonCodeResponse response = commonCodePublicService.getCode(classCode, code);
      return ResponseEntity.ok(Map.of(
          "valid", true,
          "code", response
      ));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of(
          "valid", false,
          "message", "유효하지 않은 코드입니다: " + classCode + "." + code
      ));
    }
  }
}
