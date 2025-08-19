package kr.hvy.blog.modules.admin.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.hvy.blog.modules.admin.application.dto.CommonClassUpdate;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

import java.util.ArrayList;
import java.util.List;

/**
 * 공통코드 클래스 엔티티
 * 코드 그룹을 정의하는 상위 개념
 */
@Entity
@Table(name = "common_class", uniqueConstraints = @UniqueConstraint(name = "uk_common_class_name", columnNames = "name"))
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class CommonClass {

  /**
   * 클래스명 (PK)
   * 예: REGION_CLASS, SEOUL_DISTRICT_CLASS
   */
  @Id
  @Column(nullable = false, length = 64)
  private String name;

  /**
   * 표시명
   * 예: "지역분류", "서울구분류"
   */
  @Column(length = 128)
  private String displayName;

  /**
   * 설명
   */
  @Column(length = 512)
  private String description;

  /**
   * 동적 속성 이름 정의 (Code가 가질 속성들의 이름)
   */
  @Column(length = 64)
  private String attribute1Name; // 예: "위도"

  @Column(length = 64)
  private String attribute2Name; // 예: "경도"

  @Column(length = 64)
  private String attribute3Name; // 예: "인구수"

  @Column(length = 64)
  private String attribute4Name; // 예: "면적"

  @Column(length = 64)
  private String attribute5Name; // 예: "우편번호"

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
   * 관계: 이 클래스에 속한 코드들
   */
  @OneToMany(mappedBy = "commonClass", fetch = FetchType.LAZY)
  @OrderBy("sort ASC, code ASC")
  @Builder.Default
  private List<CommonCode> codes = new ArrayList<>();

  /**
   * 엔티티 저장 전 처리
   */
  @PrePersist
  private void prePersist() {
    if (this.created == null) {
      this.created = EventLogEntity.defaultValues();
    }
    if (this.isActive == null) {
      this.isActive = true;
    }
  }

  /**
   * 엔티티 수정 전 처리
   */
  @PreUpdate
  private void preUpdate() {
    this.updated = EventLogEntity.defaultValues();
  }

  /**
   * 업데이트 메서드
   */
  public void update(CommonClassUpdate updateDto) {
    this.displayName = updateDto.getDisplayName();
    this.description = updateDto.getDescription();
    this.attribute1Name = updateDto.getAttribute1Name();
    this.attribute2Name = updateDto.getAttribute2Name();
    this.attribute3Name = updateDto.getAttribute3Name();
    this.attribute4Name = updateDto.getAttribute4Name();
    this.attribute5Name = updateDto.getAttribute5Name();
    this.isActive = updateDto.getIsActive();
  }
}
