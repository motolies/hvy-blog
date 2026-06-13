package kr.hvy.blog.modules.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import kr.hvy.blog.modules.admin.application.MasterCodeAttributeSanitizer;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.core.security.SecurityUtils;
import kr.hvy.common.infrastructure.redis.impl.masterdata.cache.MasterCodeCacheService;
import kr.hvy.common.infrastructure.redis.impl.masterdata.dto.MasterCodeTreeResponse;
import kr.hvy.common.infrastructure.redis.impl.masterdata.query.MasterCodeQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 마스터코드 트리 조회의 <b>서비스 레벨 역할 기반 sanitize</b> 를 검증한다.
 * <p>
 * 엔드포인트가 아니라 현재 인증 주체의 ROLE_ADMIN 여부({@link SecurityUtils#hasAdminRole()})로
 * sanitize 여부가 결정되는지를 {@code mockStatic} 으로 통제하여 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterCodeService 역할 기반 sanitize")
class MasterCodeServiceSanitizeTest {

  @Mock
  private MasterCodeRepository masterCodeRepository;
  @Mock
  private MasterCodeDtoMapper masterCodeDtoMapper;
  @Mock
  private MasterCodeCacheService cacheService;
  @Mock
  private MasterCodeQuery masterCodeQuery;
  @Mock
  private MasterCodeAttributeSanitizer sanitizer;

  @InjectMocks
  private MasterCodeService masterCodeService;

  private static final List<MasterCodeTreeResponse> RAW =
      List.of(MasterCodeTreeResponse.builder().code("CLAUDE").build());
  private static final List<MasterCodeTreeResponse> SANITIZED =
      List.of(MasterCodeTreeResponse.builder().code("CLAUDE").build());

  @Test
  @DisplayName("관리자면 getSubTree 는 원본을 그대로 반환하고 sanitize 하지 않는다")
  void getSubTree_admin_returnsRaw() {
    try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
      securityUtils.when(SecurityUtils::hasAdminRole).thenReturn(true);
      given(masterCodeQuery.getSubTree("CLAUDE")).willReturn(RAW);

      List<MasterCodeTreeResponse> result = masterCodeService.getSubTree("CLAUDE");

      assertThat(result).isSameAs(RAW);
      verifyNoInteractions(sanitizer);
    }
  }

  @Test
  @DisplayName("비관리자면 getSubTree 는 sanitize 된 결과를 반환한다")
  void getSubTree_nonAdmin_returnsSanitized() {
    try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
      securityUtils.when(SecurityUtils::hasAdminRole).thenReturn(false);
      given(masterCodeQuery.getSubTree("CLAUDE")).willReturn(RAW);
      given(sanitizer.sanitizeTrees(RAW)).willReturn(SANITIZED);

      List<MasterCodeTreeResponse> result = masterCodeService.getSubTree("CLAUDE");

      assertThat(result).isSameAs(SANITIZED);
    }
  }

  @Test
  @DisplayName("비관리자면 getFullTree 도 sanitize 된 결과를 반환한다")
  void getFullTree_nonAdmin_returnsSanitized() {
    try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
      securityUtils.when(SecurityUtils::hasAdminRole).thenReturn(false);
      given(masterCodeQuery.getFullTree()).willReturn(RAW);
      given(sanitizer.sanitizeTrees(RAW)).willReturn(SANITIZED);

      List<MasterCodeTreeResponse> result = masterCodeService.getFullTree();

      assertThat(result).isSameAs(SANITIZED);
    }
  }
}
