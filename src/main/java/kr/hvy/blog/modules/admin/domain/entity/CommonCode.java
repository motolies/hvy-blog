package kr.hvy.blog.modules.admin.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import kr.hvy.blog.modules.admin.application.dto.CommonCodeUpdate;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

import java.util.Objects;

/**
 * 공통코드 엔티티
 * 실제 코드값을 저장하는 엔티티
 */
@Entity
@Table(name = "common_code")
@IdClass(CommonCodeId.class)
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class CommonCode {

  /**
   * 클래스명 (복합키 1)
   */
  @Id
  @Column(name = "className", nullable = false, length = 64)
  private String className;

  /**
   * 코드값 (복합키 2)
   */
  @Id
  @Column(nullable = false, length = 32)
  private String code;

  /**
   * 코드명 (표시용)
   */
  @Column(nullable = false, length = 64)
  private String name;

  /**
   * 설명
   */
  @Column(length = 512)
  private String description;

  /**
   * 동적 속성값들 (CommonClass에서 정의한 이름의 실제 값)
   */
  @Column(length = 128)
  private String attribute1Value; // 예: "37.5665" (위도)

  @Column(length = 128)
  private String attribute2Value; // 예: "126.9780" (경도)

  @Column(length = 128)
  private String attribute3Value; // 예: "9720846" (인구수)

  @Column(length = 128)
  private String attribute4Value; // 예: "605.21" (면적)

  @Column(length = 128)
  private String attribute5Value; // 예: "03000" (우편번호)

  /**
   * 계층 구조 지원: 하위 클래스명 참조 (NULL이면 leaf 노드)
   */
  @Column(length = 64)
  private String childClassName;

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
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "DATETIME(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  /**
   * 수정 이력
   */
  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "DATETIME(6)")),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  private EventLogEntity updated;

  /**
   * 관계: 소속 클래스
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "className", referencedColumnName = "name", insertable = false, updatable = false,
      foreignKey = @ForeignKey(name = "fk_common_code_class_name"))
  private CommonClass commonClass;

  /**
   * 관계: 하위 클래스 참조 (지연로딩)
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "childClassName", referencedColumnName = "name", insertable = false, updatable = false,
      foreignKey = @ForeignKey(name = "fk_common_code_child_class_name"))
  private CommonClass childClass;

  /**
   * 엔티티 저장 전 처리
   */
  @PrePersist
  private void prePersist() {
    if (this.created == null) {
      this.created = EventLogEntity.defaultValues();
    }

    if (this.sort == null) {
      this.sort = 0;
    }
    if (this.isActive == null) {
      this.isActive = true;
    }
    validateHierarchy();
  }

  /**
   * 엔티티 수정 전 처리
   */
  @PreUpdate
  private void preUpdate() {
    this.updated = EventLogEntity.defaultValues();
    validateHierarchy();
  }

  /**
   * 하위 코드 존재 여부 판단 (계산된 필드)
   */
  public boolean hasChildren() {
    return this.childClassName != null && !this.childClassName.trim().isEmpty();
  }

  /**
   * 순환 참조 방지 검증
   */
  private void validateHierarchy() {
    if (Objects.equals(this.className, this.childClassName)) {
      throw new IllegalArgumentException("Self reference not allowed: " + this.className);
    }
  }

  /**
   * 업데이트 메서드
   */
  public void update(CommonCodeUpdate updateDto) {
    this.name = updateDto.getName();
    this.description = updateDto.getDescription();
    this.attribute1Value = updateDto.getAttribute1Value();
    this.attribute2Value = updateDto.getAttribute2Value();
    this.attribute3Value = updateDto.getAttribute3Value();
    this.attribute4Value = updateDto.getAttribute4Value();
    this.attribute5Value = updateDto.getAttribute5Value();

    this.childClassName = updateDto.getChildClassName();
    this.sort = updateDto.getSort();
    this.isActive = updateDto.getIsActive();
  }
}
