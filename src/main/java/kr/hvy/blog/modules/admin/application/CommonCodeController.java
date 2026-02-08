package kr.hvy.blog.modules.admin.application;

import kr.hvy.blog.modules.admin.application.dto.*;
import kr.hvy.blog.modules.admin.application.service.CommonCodePublicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
  public List<CommonClassResponse> getAllClasses() {
    return commonCodePublicService.getAllClasses();
  }

  /**
   * 특정 클래스 조회
   */
  @GetMapping("/class/{classCode}")
  public CommonClassResponse getClass(@PathVariable String classCode) {
    return commonCodePublicService.getClass(classCode);
  }

  // ========== 코드 조회 API ==========

  /**
   * 클래스별 코드 목록 조회
   */
  @GetMapping("/class/{classCode}/codes")
  public List<CommonCodeResponse> getCodesByClass(@PathVariable String classCode) {
    return commonCodePublicService.getCodesByClass(classCode);
  }

  /**
   * 특정 코드 조회
   */
  @GetMapping("/code/{classCode}/{code}")
  public CommonCodeResponse getCode(
      @PathVariable String classCode,
      @PathVariable String code) {
    return commonCodePublicService.getCode(classCode, code);
  }

  /**
   * 하위 코드 조회
   */
  @GetMapping("/code/{classCode}/{code}/children")
  public List<CommonCodeResponse> getChildCodes(
      @PathVariable String classCode,
      @PathVariable String code) {
    return commonCodePublicService.getChildCodes(classCode, code);
  }

  // ========== 트리 구조 조회 API ==========

  /**
   * 계층 구조 트리 조회
   */
  @GetMapping("/class/{classCode}/tree")
  public CommonCodeTreeResponse getCodesWithTree(@PathVariable String classCode) {
    return commonCodePublicService.getCodesWithTree(classCode);
  }

  /**
   * 평면 구조 조회 (select box용)
   */
  @GetMapping("/class/{classCode}/flat")
  public List<CommonCodeTreeResponse.CommonCodeFlatResponse> getFlatCodes(@PathVariable String classCode) {
    return commonCodePublicService.getFlatCodes(classCode);
  }

  // ========== 검색 API ==========

  /**
   * 클래스 내 코드명 검색
   */
  @GetMapping("/class/{classCode}/search")
  public List<CommonCodeResponse> searchCodesInClass(
      @PathVariable String classCode,
      @RequestParam String q) {
    return commonCodePublicService.searchCodesInClass(classCode, q);
  }

  /**
   * 전체 클래스에서 코드명 검색
   */
  @GetMapping("/search")
  public List<CommonCodeResponse> searchCodesAcrossAllClasses(@RequestParam String q) {
    return commonCodePublicService.searchCodesAcrossAllClasses(q);
  }

  /**
   * 속성값으로 코드 검색
   */
  @GetMapping("/class/{classCode}/search-by-attribute")
  public List<CommonCodeResponse> searchCodesByAttribute(
      @PathVariable String classCode,
      @RequestParam String attributeValue) {
    return commonCodePublicService.searchCodesByAttribute(classCode, attributeValue);
  }

  // ========== 통계 API ==========

  /**
   * 클래스별 코드 개수 통계
   */
  @GetMapping("/statistics/count-by-class")
  public Map<String, Long> getCodeCountByClass() {
    return commonCodePublicService.getCodeCountByClass();
  }

  // ========== 편의 API ==========

  /**
   * 여러 클래스의 코드를 한번에 조회
   */
  @PostMapping("/codes/batch")
  public Map<String, List<CommonCodeResponse>> getBatchCodes(@RequestBody List<String> classCodes) {
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

    return result;
  }

  /**
   * 코드 값 유효성 검증
   */
  @GetMapping("/validate/{classCode}/{code}")
  public Map<String, Object> validateCode(
      @PathVariable String classCode,
      @PathVariable String code) {
    try {
      CommonCodeResponse response = commonCodePublicService.getCode(classCode, code);
      return Map.of(
          "valid", true,
          "code", response
      );
    } catch (Exception e) {
      return Map.of(
          "valid", false,
          "message", "유효하지 않은 코드입니다: " + classCode + "." + code
      );
    }
  }
}
