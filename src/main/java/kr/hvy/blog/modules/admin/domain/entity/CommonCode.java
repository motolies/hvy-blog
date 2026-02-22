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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import org.apache.commons.lang3.ObjectUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

/**
 * 공통코드 엔티티 실제 코드값을 저장하는 엔티티
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_common_code_class_code", columnNames = {"classId", "code"}))
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class CommonCode {

  /**
   * 내부 ID (PK, Surrogate Key)
   */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 코드값 (Natural Key)
   */
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
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  /**
   * 수정 이력
   */
  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "TIMESTAMP(6)")),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  private EventLogEntity updated;

  /**
   * 관계: 소속 클래스 (ID 기반 FK)
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "classId", nullable = false,
      foreignKey = @ForeignKey(name = "fk_common_code_class_id"))
  private CommonClass commonClass;

  /**
   * 관계: 하위 클래스 참조 (ID 기반 FK)
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "childClassId",
      foreignKey = @ForeignKey(name = "fk_common_code_child_class_id"))
  private CommonClass childClass;

  /**
   * 엔티티 저장 전 처리
   */
  @PrePersist
  private void prePersist() {
    if (ObjectUtils.isEmpty(this.created)) {
      this.created = EventLogEntity.defaultValues();
    }

    if (ObjectUtils.isEmpty(this.sort)) {
      this.sort = 0;
    }
    if (ObjectUtils.isEmpty(this.isActive)) {
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
    return ObjectUtils.isNotEmpty(this.childClass);
  }

  /**
   * 순환 참조 방지 검증
   */
  private void validateHierarchy() {
    if (ObjectUtils.isNotEmpty(this.commonClass) && ObjectUtils.isNotEmpty(this.childClass) &&
        Objects.equals(this.commonClass.getId(), this.childClass.getId())) {
      throw new IllegalArgumentException("Self reference not allowed: class ID " + this.commonClass.getId());
    }
  }

  /**
   * 업데이트 메서드 Note: commonClass와 childClass는 Service 레이어에서 설정
   */
  public void update(String code, String name, String description,
      String attribute1Value, String attribute2Value, String attribute3Value,
      String attribute4Value, String attribute5Value,
      Integer sort, Boolean isActive) {
    // code 필드도 업데이트 가능 (Surrogate Key 패턴)
    if (ObjectUtils.isNotEmpty(code) && !code.trim().isEmpty()) {
      this.code = code;
    }
    this.name = name;
    this.description = description;
    this.attribute1Value = attribute1Value;
    this.attribute2Value = attribute2Value;
    this.attribute3Value = attribute3Value;
    this.attribute4Value = attribute4Value;
    this.attribute5Value = attribute5Value;

    this.sort = sort;
    this.isActive = isActive;
    // childClass는 Service에서 별도로 설정
  }
}
