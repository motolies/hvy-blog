package kr.hvy.blog.modules.hotdeal.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.code.converter.DealSiteCodeConverter;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HotDealSite {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 스크래퍼를 고르는 키다 — 게시판 식별자가 아니다.
   * <p>
   * <b>unique 를 걸지 않는다.</b> 한 코드가 게시판 여러 개를 가질 수 있다 —
   * 뽐뿌는 국내(id=ppomppu)와 해외(id=ppomppu4) 게시판이 같은 PPOMPPU 코드로 등록되어
   * 같은 스크래퍼를 쓰되 boardUrl 만 다르다(db/hotdeal-schema.sql 시드 참조).
   * 운영 DDL 에도 이 제약은 없으며, 여기에 unique=true 를 두면 테스트(H2 create-drop)에서만
   * 제약이 생겨 환경 간 스키마가 갈린다 — 운영은 ddl-auto:validate 라 UNIQUE 를 검증하지 않는다.
   */
  @Convert(converter = DealSiteCodeConverter.class)
  @Column(nullable = false, length = 32)
  private DealSiteCode siteCode;

  @Column(nullable = false, length = 128)
  private String siteName;

  @Column(nullable = false, length = 512)
  private String siteUrl;

  @Column(nullable = false, length = 512)
  private String boardUrl;

  @Column(nullable = false)
  @Builder.Default
  private boolean enabled = true;

  @Column(nullable = false)
  @Builder.Default
  private boolean requiresLogin = false;

  @Column(length = 128)
  private String loginId;

  @Column(length = 256)
  private String loginPassword;

  @Column(nullable = false)
  @Builder.Default
  private int minRecommendation = 10;

  @Column(nullable = false)
  @Builder.Default
  private int minViewCount = 1000;

  @Column(nullable = false)
  @Builder.Default
  private int minCommentCount = 25;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();
}
