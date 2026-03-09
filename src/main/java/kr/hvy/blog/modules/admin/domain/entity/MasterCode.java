package kr.hvy.blog.modules.admin.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
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

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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
   * Materialized Path (예: /1/5/12)
   */
  @Column(length = 512)
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
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "TIMESTAMP(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy", length = 64))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  /**
   * 수정 이력
   */
  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "TIMESTAMP(6)")),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy", length = 64))
  })
  private EventLogEntity updated;

  @PrePersist
  private void prePersist() {
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
    this.name = name;
    this.description = description;
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
   * depth와 path를 부모 기준으로 재계산
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
