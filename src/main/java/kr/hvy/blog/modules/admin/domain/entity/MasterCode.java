package kr.hvy.blog.modules.admin.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import kr.hvy.common.core.converter.TsidUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.ObjectUtils;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 마스터코드 엔티티 (자기참조 트리 구조)
 * 기존 CommonClass + CommonCode 2테이블을 단일 테이블로 통합
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterCode {

  /**
   * TSID 문자열 PK (Crockford Base32 고정 13자).
   * <p>
   * IDENTITY 를 쓰지 않는 이유는 <b>INSERT 전에 id 를 알아야 하기 때문</b>이다. path 가
   * {@code /부모path/자기id} 라서, id 를 INSERT 후에야 알면 path 를 뒤따르는 UPDATE 로 채울 수밖에
   * 없고 그 UPDATE 를 빠뜨리면 path 가 NULL 로 남아 서브트리 조회가 통째로 죽는다(실제 사고).
   * 애플리케이션이 id 를 만들면 path 를 같은 INSERT 에 넣을 수 있어 {@code path NOT NULL} 로
   * DB 가 직접 그 사고를 거부한다.
   * <p>
   * 고정 13자라 Materialized Path 의 prefix 충돌({@code '/6%'} 가 {@code /60...} 을 삼키는 문제)도
   * 구조적으로 생기지 않는다. 같은 구조인 {@code Category} 엔티티가 이미 이 방식을 쓴다.
   */
  @Id
  @Column(nullable = false, length = 13)
  private String id;

  /**
   * 부모 노드 (NULL이면 루트)
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id",
      foreignKey = @ForeignKey(name = "fk_master_code_parent"))
  private MasterCode parent;

  /**
   * 자식 노드 목록
   */
  @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
  @OrderBy("sort ASC, code ASC")
  @Builder.Default
  private List<MasterCode> children = new ArrayList<>();

  /**
   * 트리 깊이 (0=루트, 1+=하위)
   */
  @Column(nullable = false)
  @Builder.Default
  private Integer depth = 0;

  /**
   * Materialized Path (예: /0RF87Y7EXVPB9/0RF880A9HVQCF)
   * <p>
   * ★ NOT NULL 이다. 이 제약이 "path 를 채우는 UPDATE 를 빠뜨림"을 DB 레벨에서 거부한다 —
   * 서브트리 조회({@code findSubtree})가 전적으로 이 값에 의존하기 때문이다.
   * 세그먼트가 14자(구분자 포함)라 512 안에 36단계까지 들어간다.
   */
  @Column(nullable = false, length = 512)
  private String path;

  /**
   * 코드값
   */
  @Column(nullable = false, length = 64)
  private String code;

  /**
   * 코드명
   */
  @Column(nullable = false, length = 128)
  private String name;

  /**
   * 설명
   */
  @Column(length = 512)
  private String description;

  /**
   * 코드별 속성값 (JSONB)
   * 예: {"latitude":"37.5665", "longitude":"126.9780"}
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  @Builder.Default
  private Map<String, Object> attributes = new HashMap<>();

  /**
   * 루트 노드 전용: 속성 스키마 정의 (JSONB)
   * 예: [{"key":"latitude","label":"위도","type":"text"}]
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  @Builder.Default
  private List<Map<String, String>> attributeSchema = new ArrayList<>();

  /**
   * 정렬순서
   */
  @Column(nullable = false)
  @Builder.Default
  private Integer sort = 0;

  /**
   * 활성화 여부
   */
  @Column(nullable = false)
  @Builder.Default
  private Boolean isActive = true;

  /**
   * 생성 이력
   */
  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy", length = 64))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  /**
   * 수정 이력
   */
  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt")),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy", length = 64))
  })
  private EventLogEntity updated;

  @PrePersist
  private void prePersist() {
    // id 를 여기서 만들기 때문에 같은 시점에 path 까지 확정할 수 있다.
    // (IDENTITY 였을 때는 save→flush→refresh→recalculate→save 로 두 번 저장해야 했다.)
    if (ObjectUtils.isEmpty(this.id)) {
      this.id = TsidUtils.getTsid().toString();
    }
    recalculateTreeFields();
    if (ObjectUtils.isEmpty(this.created)) {
      this.created = EventLogEntity.defaultValues();
    }
    if (ObjectUtils.isEmpty(this.isActive)) {
      this.isActive = true;
    }
    if (ObjectUtils.isEmpty(this.sort)) {
      this.sort = 0;
    }
    if (ObjectUtils.isEmpty(this.depth)) {
      this.depth = 0;
    }
    if (this.attributes == null) {
      this.attributes = new HashMap<>();
    }
    if (this.attributeSchema == null) {
      this.attributeSchema = new ArrayList<>();
    }
  }

  @PreUpdate
  private void preUpdate() {
    this.updated = EventLogEntity.defaultValues();
  }

  /**
   * 루트 노드 여부 (parent가 NULL이면 루트)
   */
  public boolean isRoot() {
    return this.parent == null;
  }

  /**
   * 리프 노드 여부 (자식이 없으면 리프)
   */
  public boolean isLeaf() {
    return this.children == null || this.children.isEmpty();
  }

  /**
   * 노드 정보 업데이트
   */
  public void update(String code, String name, String description,
      Map<String, Object> attributes, List<Map<String, String>> attributeSchema,
      Integer sort, Boolean isActive) {
    if (ObjectUtils.isNotEmpty(code) && !code.trim().isEmpty()) {
      this.code = code;
    }
    if (name != null) {
      this.name = name;
    }
    if (description != null) {
      this.description = description;
    }
    if (attributes != null) {
      this.attributes = attributes;
    }
    if (attributeSchema != null) {
      this.attributeSchema = attributeSchema;
    }
    if (sort != null) {
      this.sort = sort;
    }
    if (isActive != null) {
      this.isActive = isActive;
    }
  }

  /**
   * depth와 path를 부모 기준으로 재계산.
   * <p>
   * {@code @PrePersist} 가 INSERT 직전에 부르므로 신규 노드는 따로 호출할 필요가 없다.
   * 부모가 바뀌는 이동({@code moveNode})에서만 명시적으로 부른다.
   * <p>
   * 부모 path 는 NOT NULL 이라 예전처럼 {@code "null/..."} 문자열이 만들어질 수 없다.
   */
  public void recalculateTreeFields() {
    if (this.parent == null) {
      this.depth = 0;
      this.path = "/" + this.id;
    } else {
      this.depth = this.parent.getDepth() + 1;
      this.path = this.parent.getPath() + "/" + this.id;
    }
  }
}
