package kr.hvy.blog.modules.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.admin.application.MasterCodeAttributeSanitizer;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeChildrenOrderRequest;
import kr.hvy.blog.modules.admin.domain.entity.MasterCode;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.infrastructure.redis.impl.masterdata.cache.MasterCodeCacheService;
import kr.hvy.common.infrastructure.redis.impl.masterdata.query.MasterCodeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link MasterCodeService#reorderChildren} 검증.
 * <p>
 * 이 메서드의 어려운 부분은 정렬 자체가 아니라 <b>어떤 입력을 거부하고 어떤 입력을 조용히 넘길지</b> 다.
 * 이 프로젝트는 400·500 을 가리지 않고 모든 예외가 Slack #hvy-error 를 울리므로,
 * "다른 탭에서 노드를 지운 뒤 드래그" 같은 평범한 레이스를 예외로 만들면 그것이 곧 장애 알림이 된다.
 * 아래 케이스 5(삭제된 id)와 케이스 2(비활성 형제)가 그 경계를 못 박는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterCodeService 형제 정렬순서 일괄 변경")
class MasterCodeServiceReorderTest {

  private static final String PARENT_ID = "0RF87Y7EXVPB9";

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
  @Mock
  private EntityManager entityManager;

  @InjectMocks
  private MasterCodeService masterCodeService;

  private MasterCode parent;

  @BeforeEach
  void setUp() {
    // @PersistenceContext 필드 주입이라 생성자로는 들어가지 않는다.
    ReflectionTestUtils.setField(masterCodeService, "entityManager", entityManager);
    parent = node(PARENT_ID, "FAVORITE", 1);
  }

  /** 부모가 루트인 노드 하나. findRootCode 가 parent 를 타고 올라가므로 parent 는 비워 둔다. */
  private static MasterCode node(String id, String code, int sort) {
    return MasterCode.builder().id(id).code(code).name(code).sort(sort).build();
  }

  private static MasterCodeChildrenOrderRequest request(String... ids) {
    return MasterCodeChildrenOrderRequest.builder().orderedIds(List.of(ids)).build();
  }

  private void givenParentExists() {
    given(masterCodeRepository.findById(PARENT_ID)).willReturn(Optional.of(parent));
  }

  private void givenChildren(MasterCode... children) {
    given(masterCodeRepository.findByParentIdOrderBySortAscCodeAsc(PARENT_ID))
        .willReturn(List.of(children));
  }

  @Test
  @DisplayName("요청 순서대로 sort 가 1..n 으로 재부여된다")
  void reorder_assignsSequentialSort() {
    MasterCode a = node("A", "ALPHA", 1);
    MasterCode b = node("B", "BRAVO", 2);
    MasterCode c = node("C", "CHARLIE", 3);
    givenParentExists();
    givenChildren(a, b, c);

    masterCodeService.reorderChildren(PARENT_ID, request("C", "A", "B"));

    assertThat(c.getSort()).isEqualTo(1);
    assertThat(a.getSort()).isEqualTo(2);
    assertThat(b.getSort()).isEqualTo(3);
  }

  @Test
  @DisplayName("요청에 없는 형제(관리 화면이 못 보는 비활성 노드)는 예외 없이 뒤에 이어붙는다")
  void reorder_appendsSiblingsMissingFromRequest() {
    MasterCode a = node("A", "ALPHA", 1);
    MasterCode hidden = node("H", "HIDDEN", 2); // isActive=false 라 클라이언트에 내려가지 않는다
    MasterCode b = node("B", "BRAVO", 3);
    givenParentExists();
    givenChildren(a, hidden, b);

    masterCodeService.reorderChildren(PARENT_ID, request("B", "A"));

    assertThat(b.getSort()).isEqualTo(1);
    assertThat(a.getSort()).isEqualTo(2);
    // 이어붙이지 않으면 hidden 의 sort 가 1..n 블록과 겹쳐 code 폴백으로 순서가 무너진다.
    assertThat(hidden.getSort()).isEqualTo(3);
    verify(cacheService).evictByRootCode(anyString());
  }

  @Test
  @DisplayName("중복 ID 가 있으면 거부한다 — 두 노드가 같은 sort 를 받아 순서가 조용히 무너지기 때문")
  void reorder_rejectsDuplicateIds() {
    givenParentExists();

    assertThatThrownBy(() -> masterCodeService.reorderChildren(PARENT_ID, request("A", "A", "B")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("중복된 ID");

    verify(cacheService, never()).evictByRootCode(anyString());
  }

  @Test
  @DisplayName("다른 부모의 자식 ID 가 섞이면 거부한다 (DB 에 존재하므로 클라이언트 버그 또는 조작)")
  void reorder_rejectsForeignChildId() {
    MasterCode a = node("A", "ALPHA", 1);
    givenParentExists();
    givenChildren(a);
    given(masterCodeRepository.existsById("FOREIGN")).willReturn(true);

    assertThatThrownBy(() -> masterCodeService.reorderChildren(PARENT_ID, request("A", "FOREIGN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("자식이 아닌 ID");

    verify(cacheService, never()).evictByRootCode(anyString());
  }

  @Test
  @DisplayName("★ 이미 삭제된 ID 는 예외 없이 건너뛰고 나머지를 정렬한다 (평범한 레이스 — Slack 을 울리면 안 된다)")
  void reorder_skipsDeletedIdWithoutThrowing() {
    MasterCode a = node("A", "ALPHA", 1);
    MasterCode b = node("B", "BRAVO", 2);
    givenParentExists();
    givenChildren(a, b);
    given(masterCodeRepository.existsById("GONE")).willReturn(false);

    masterCodeService.reorderChildren(PARENT_ID, request("B", "GONE", "A"));

    assertThat(b.getSort()).isEqualTo(1);
    assertThat(a.getSort()).isEqualTo(2);
    verify(cacheService).evictByRootCode(anyString());
  }

  @Test
  @DisplayName("부모 노드가 없으면 DataNotFoundException")
  void reorder_missingParent() {
    given(masterCodeRepository.findById(PARENT_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> masterCodeService.reorderChildren(PARENT_ID, request("A")))
        .isInstanceOf(DataNotFoundException.class);
  }

  @Test
  @DisplayName("캐시 무효화는 정확히 1회다 — 정렬은 부모를 바꾸지 않아 rootCode 가 불변이다")
  void reorder_evictsCacheExactlyOnce() {
    MasterCode a = node("A", "ALPHA", 1);
    MasterCode b = node("B", "BRAVO", 2);
    MasterCode c = node("C", "CHARLIE", 3);
    givenParentExists();
    givenChildren(a, b, c);

    masterCodeService.reorderChildren(PARENT_ID, request("C", "B", "A"));

    verify(cacheService, times(1)).evictByRootCode("FAVORITE");
  }

  @Test
  @DisplayName("sort 가 전부 동률이거나 구멍이 있어도 1..n 으로 정규화된다")
  void reorder_normalizesTiedAndGappedSort() {
    MasterCode tiedA = node("A", "ALPHA", 0);
    MasterCode tiedB = node("B", "BRAVO", 0);
    MasterCode gapped = node("C", "CHARLIE", 9);
    givenParentExists();
    givenChildren(tiedA, tiedB, gapped);

    masterCodeService.reorderChildren(PARENT_ID, request("C", "B", "A"));

    assertThat(gapped.getSort()).isEqualTo(1);
    assertThat(tiedB.getSort()).isEqualTo(2);
    assertThat(tiedA.getSort()).isEqualTo(3);
  }

  @Test
  @DisplayName("flush 가 캐시 무효화보다 먼저다 — 제약 위반이면 캐시를 비우기 전에 드러나야 한다")
  void reorder_flushesBeforeEvict() {
    MasterCode a = node("A", "ALPHA", 1);
    MasterCode b = node("B", "BRAVO", 2);
    givenParentExists();
    givenChildren(a, b);

    masterCodeService.reorderChildren(PARENT_ID, request("B", "A"));

    InOrder order = inOrder(entityManager, cacheService);
    order.verify(entityManager).flush();
    order.verify(cacheService).evictByRootCode("FAVORITE");
  }
}
