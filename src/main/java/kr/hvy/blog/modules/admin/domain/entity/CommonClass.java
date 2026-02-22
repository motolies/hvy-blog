package kr.hvy.blog.modules.admin.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import org.apache.commons.lang3.ObjectUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

/**
 * 공통코드 클래스 엔티티 코드 그룹을 정의하는 상위 개념
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_common_class_code", columnNames = "code"))
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class CommonClass {

  /**
   * 내부 ID (PK, Surrogate Key)
   */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 클래스 코드 (Unique, Natural Key) 예: REGION_CLASS, SEOUL_DISTRICT_CLASS
   */
  @Column(nullable = false, unique = true, length = 64)
  private String code;

  /**
   * 클래스명 예: "지역분류", "서울구분류"
   */
  @Column(length = 128)
  private String name;

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
    if (ObjectUtils.isEmpty(this.created)) {
      this.created = EventLogEntity.defaultValues();
    }
    if (ObjectUtils.isEmpty(this.isActive)) {
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
  public void update(String code, String name, String description,
      String attribute1Name, String attribute2Name, String attribute3Name,
      String attribute4Name, String attribute5Name, Boolean isActive) {
    // code 필드도 업데이트 가능 (Surrogate Key 패턴)
    if (ObjectUtils.isNotEmpty(code) && !code.trim().isEmpty()) {
      this.code = code;
    }
    this.name = name;
    this.description = description;
    this.attribute1Name = attribute1Name;
    this.attribute2Name = attribute2Name;
    this.attribute3Name = attribute3Name;
    this.attribute4Name = attribute4Name;
    this.attribute5Name = attribute5Name;
    this.isActive = isActive;
  }
}
