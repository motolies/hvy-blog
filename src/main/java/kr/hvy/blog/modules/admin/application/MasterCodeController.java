package kr.hvy.blog.modules.admin.application;

import java.util.List;
import java.util.Set;
import kr.hvy.blog.modules.admin.application.service.MasterCodeService;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마스터코드 공개 조회 Controller (/api/codes).
 * <p>
 * 민감 attribute 제거는 {@link MasterCodeService} 가 현재 사용자의 ROLE_ADMIN 여부로 서비스 레벨에서 수행한다
 * (비관리자=sanitize, 관리자=원본). 이 컨트롤러는 화이트리스트 검사 후 서비스에 위임만 한다.
 * 전체 트리/그룹/검색/노드 단건 조회 및 모든 쓰기는 admin 전용({@link AdminMasterCodeController}, /api/codes/admin)으로 분리되어
 * SecurityConfig 의 {@code /api/{module}/admin} 규칙으로 보호된다.
 * <p>
 * 또한 공개 조회는 {@code hvy.master-code.public-roots} 화이트리스트에 등록된 루트만 허용한다.
 * 미등록 루트(예: CLAUDE)는 sanitize 와 무관하게 구조(id/code/name)조차 노출하지 않기 위해 빈 목록을 반환한다.
 */
@RestController
@RequestMapping("/api/codes")
@RequiredArgsConstructor
public class MasterCodeController {

  private final MasterCodeService masterCodeService;

  // 공개 조회를 허용할 루트코드 화이트리스트(쉼표구분). 기본은 즐겨찾기(FAVORITE) 만 공개한다.
  // 코드는 대문자 규약이므로 비교 시 대문자로 정규화한다.
  @Value("${hvy.master-code.public-roots:FAVORITE}")
  private Set<String> publicRoots;

  /**
   * 루트별 서브트리 조회 (공개). 비관리자 응답은 서비스에서 민감 attribute 가 제거되어 반환된다.
   * (예: 공개 페이지의 즐겨찾기 'FAVORITE' 조회)
   * 화이트리스트에 없는 루트는 빈 목록을 반환한다.
   */
  @GetMapping("/tree/{rootCode}")
  public List<MasterCodeTreeResponse> getSubTree(@PathVariable String rootCode) {
    if (rootCode == null || !publicRoots.contains(rootCode.toUpperCase())) {
      return List.of();
    }
    return masterCodeService.getSubTree(rootCode);
  }
}
