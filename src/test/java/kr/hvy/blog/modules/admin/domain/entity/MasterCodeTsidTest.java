package kr.hvy.blog.modules.admin.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MasterCode 의 TSID PK 와 Materialized Path 조립을 잠그는 단위 테스트.
 * <p>
 * 이 테스트가 지키려는 사고: id 가 IDENTITY 이던 시절에는 INSERT 후에야 id 를 알아
 * path 를 별도 UPDATE 로 채워야 했고, 그 UPDATE 를 빠뜨리면 path 가 NULL 로 남아
 * 서브트리 조회(findSubtree 의 path LIKE)가 통째로 0건이 되었다. 전체 트리 조회는
 * parent_id 로만 조립해 멀쩡히 보였기 때문에 한쪽 화면에서만 데이터가 사라졌다.
 * <p>
 * DB 없이 도는 순수 단위 테스트다 — 이 저장소는 Testcontainers 가 로컬 Docker 를 못 잡고
 * 테스트 DB 도 H2 라 PG 전용 동작은 어차피 검증되지 않는다.
 */
@DisplayName("MasterCode TSID PK 와 path 조립")
class MasterCodeTsidTest {

  /** JPA 가 INSERT 직전에 부르는 콜백. private 이라 리플렉션으로 호출한다. */
  private static void prePersist(MasterCode entity) {
    ReflectionTestUtils.invokeMethod(entity, "prePersist");
  }

  @Test
  @DisplayName("루트는 id 를 스스로 만들고 path 를 '/id' 로 확정한다 — INSERT 한 번으로 끝난다")
  void root_generatesIdAndPath() {
    MasterCode root = MasterCode.builder().code("PLATFORM").name("플랫폼").build();

    prePersist(root);

    assertThat(root.getId()).isNotBlank().hasSize(13);
    assertThat(root.getDepth()).isZero();
    assertThat(root.getPath()).isEqualTo("/" + root.getId());
  }

  @Test
  @DisplayName("자식 path 는 부모 path 를 이어붙이고 depth 는 부모+1 이다")
  void child_buildsPathFromParent() {
    MasterCode root = MasterCode.builder().code("PLATFORM").name("플랫폼").build();
    prePersist(root);

    MasterCode child = MasterCode.builder().parent(root).code("DEPLOY").name("배포").build();
    prePersist(child);

    assertThat(child.getDepth()).isEqualTo(1);
    assertThat(child.getPath()).isEqualTo(root.getPath() + "/" + child.getId());

    MasterCode grandChild = MasterCode.builder().parent(child).code("ARGOCD").name("ArgoCD").build();
    prePersist(grandChild);

    assertThat(grandChild.getDepth()).isEqualTo(2);
    assertThat(grandChild.getPath()).isEqualTo(child.getPath() + "/" + grandChild.getId());
    // 서브트리 조회가 성립하려면 자손 path 가 루트 path 로 시작해야 한다.
    assertThat(grandChild.getPath()).startsWith(root.getPath() + "/");
  }

  @Test
  @DisplayName("id 를 미리 지정하면 그대로 쓴다 — 시드 SQL 이 고정 id 를 넣는 경우")
  void explicitId_isPreserved() {
    MasterCode seeded = MasterCode.builder().id("0000000000001").code("REGION").name("지역").build();

    prePersist(seeded);

    assertThat(seeded.getId()).isEqualTo("0000000000001");
    assertThat(seeded.getPath()).isEqualTo("/0000000000001");
  }

  @Test
  @DisplayName("path 는 어떤 경우에도 null 이 아니다 — DB 의 NOT NULL 과 짝을 이룬다")
  void path_isNeverNull() {
    MasterCode root = MasterCode.builder().code("A").name("a").build();
    prePersist(root);
    MasterCode child = MasterCode.builder().parent(root).code("B").name("b").build();
    prePersist(child);

    assertThat(root.getPath()).isNotNull();
    assertThat(child.getPath()).isNotNull();
    // 부모 path 가 null 이던 시절의 "null/123" 문자열 오염이 재현되지 않는지.
    assertThat(child.getPath()).doesNotContain("null");
  }

  @Test
  @DisplayName("id 는 고정 13자라 형제 path 가 서로의 prefix 가 되지 않는다")
  void fixedWidthId_preventsPrefixCollision() {
    MasterCode a = MasterCode.builder().code("A").name("a").build();
    MasterCode b = MasterCode.builder().code("B").name("b").build();
    prePersist(a);
    prePersist(b);

    assertThat(a.getId()).hasSize(13);
    assertThat(b.getId()).hasSize(13);
    // 길이가 같으면 서로 다른 값은 결코 상대의 prefix 가 될 수 없다
    // ('/6' 이 '/60' 을 삼키던 문제가 구조적으로 사라진다).
    assertThat(a.getPath()).isNotEqualTo(b.getPath());
    assertThat(b.getPath()).doesNotStartWith(a.getPath() + "/");
    assertThat(a.getPath()).doesNotStartWith(b.getPath() + "/");
  }
}
