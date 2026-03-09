package kr.hvy.blog.modules.admin.application;

import jakarta.validation.Valid;
import java.util.List;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeCreate;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeMoveRequest;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeTreeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeUpdate;
import kr.hvy.blog.modules.admin.application.service.MasterCodeCacheService;
import kr.hvy.blog.modules.admin.application.service.MasterCodeService;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마스터코드 Controller
 * /api/v1/code 엔드포인트
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/codes")
@RequiredArgsConstructor
public class MasterCodeController {

  private final MasterCodeService masterCodeService;
  private final MasterCodeCacheService masterCodeCacheService;

  // ========== 트리 조회 ==========

  /**
   * 전체 트리 조회
   */
  @GetMapping("/tree")
  public List<MasterCodeTreeResponse> getFullTree() {
    return masterCodeService.getFullTree();
  }

  /**
   * 루트별 서브트리 조회
   */
  @GetMapping("/tree/{rootCode}")
  public List<MasterCodeTreeResponse> getSubTree(@PathVariable String rootCode) {
    return masterCodeService.getSubTree(rootCode);
  }

  /**
   * 루트별 플랫 목록 조회 (select box용)
   */
  @GetMapping("/tree/{rootCode}/flat")
  public List<MasterCodeResponse> getFlatCodes(@PathVariable String rootCode) {
    return masterCodeService.getFlatCodes(rootCode);
  }

  /**
   * 루트 목록 조회
   */
  @GetMapping("/groups")
  public List<MasterCodeResponse> getGroups() {
    return masterCodeService.getGroups();
  }

  // ========== 노드 CRUD ==========

  /**
   * 노드 생성
   */
  @PostMapping("/nodes")
  @ResponseStatus(HttpStatus.CREATED)
  public MasterCodeResponse createNode(@Valid @RequestBody MasterCodeCreate createDto) {
    return masterCodeService.createNode(createDto);
  }

  /**
   * 노드 상세 조회
   */
  @GetMapping("/nodes/{id}")
  public MasterCodeResponse getNode(@PathVariable Long id) {
    return masterCodeService.getNode(id);
  }

  /**
   * 노드 수정
   */
  @PutMapping("/nodes/{id}")
  public MasterCodeResponse updateNode(
      @PathVariable Long id,
      @Valid @RequestBody MasterCodeUpdate updateDto) {
    return masterCodeService.updateNode(id, updateDto);
  }

  /**
   * 노드 삭제
   */
  @DeleteMapping("/nodes/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteNode(@PathVariable Long id) {
    masterCodeService.deleteNode(id);
  }

  /**
   * 노드 이동 (부모 변경)
   */
  @PutMapping("/nodes/{id}/move")
  public MasterCodeResponse moveNode(
      @PathVariable Long id,
      @Valid @RequestBody MasterCodeMoveRequest moveRequest) {
    return masterCodeService.moveNode(id, moveRequest);
  }

  // ========== 검색 ==========

  /**
   * 이름/코드 검색
   */
  @GetMapping("/search")
  public List<MasterCodeResponse> search(@RequestParam String q) {
    return masterCodeService.searchNodes(q);
  }

  // ========== 캐시 관리 ==========

  /**
   * 전체 캐시 초기화 (관리자용)
   */
  @DeleteMapping("/cache")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void evictAllCache() {
    masterCodeCacheService.evictAll();
    log.info("마스터코드 전체 캐시 초기화");
  }
}
