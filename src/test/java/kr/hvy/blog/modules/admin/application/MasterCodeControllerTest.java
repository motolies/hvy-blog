package kr.hvy.blog.modules.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.hvy.blog.modules.admin.application.service.MasterCodeService;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 공개 마스터코드 컨트롤러의 화이트리스트 단락(short-circuit)과 서비스 위임을 검증하는 단위 테스트.
 * <p>
 * 민감 attribute 제거(sanitize)는 더 이상 컨트롤러가 아니라 {@link MasterCodeService} 가 ROLE_ADMIN 여부로
 * 수행하므로(별도 {@code MasterCodeServiceSanitizeTest} 에서 검증), 여기서는 화이트리스트/위임만 잠근다.
 * 인가 규칙(admin → ROLE_ADMIN)은 SecurityConfig 선언으로 강제되며 통합 테스트의 영역으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterCodeController 공개 조회 화이트리스트")
class MasterCodeControllerTest {

  @Mock
  private MasterCodeService masterCodeService;

  private MasterCodeController controller;

  @BeforeEach
  void setUp() {
    controller = new MasterCodeController(masterCodeService);
    // @Value 로 주입되는 화이트리스트를 테스트에서 직접 설정한다(기본 FAVORITE).
    ReflectionTestUtils.setField(controller, "publicRoots", new HashSet<>(Set.of("FAVORITE")));
  }

  @Test
  @DisplayName("화이트리스트 루트는 서비스 결과를 그대로 위임 반환한다")
  void whitelistedRoot_delegatesToService() {
    // Given: 서비스가 역할 기반 sanitize 까지 마친 결과를 돌려준다고 가정
    List<MasterCodeTreeResponse> serviceResult =
        List.of(MasterCodeTreeResponse.builder().code("FAVORITE").build());
    given(masterCodeService.getSubTree("FAVORITE")).willReturn(serviceResult);

    // When
    List<MasterCodeTreeResponse> result = controller.getSubTree("FAVORITE");

    // Then
    assertThat(result).isSameAs(serviceResult);
    verify(masterCodeService).getSubTree("FAVORITE");
  }

  @Test
  @DisplayName("화이트리스트 미등록 루트는 서비스 호출 없이 빈 목록을 반환한다(누설 차단)")
  void nonWhitelistedRoot_returnsEmptyWithoutInteraction() {
    // When
    List<MasterCodeTreeResponse> result = controller.getSubTree("CLAUDE");

    // Then
    assertThat(result).isEmpty();
    verifyNoInteractions(masterCodeService);
  }

  @Test
  @DisplayName("루트코드 대소문자는 무시하고 화이트리스트와 비교한다")
  void rootCode_isCaseInsensitive() {
    given(masterCodeService.getSubTree("favorite")).willReturn(List.of());

    List<MasterCodeTreeResponse> result = controller.getSubTree("favorite");

    assertThat(result).isEmpty();
    verify(masterCodeService).getSubTree("favorite");
  }

  @Test
  @DisplayName("null 루트코드는 호출 없이 빈 목록을 반환한다")
  void nullRoot_returnsEmpty() {
    List<MasterCodeTreeResponse> result = controller.getSubTree(null);

    assertThat(result).isEmpty();
    verifyNoInteractions(masterCodeService);
  }
}
