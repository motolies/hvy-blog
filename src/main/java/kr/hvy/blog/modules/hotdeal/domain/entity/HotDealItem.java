package kr.hvy.blog.modules.hotdeal.domain.entity;

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
import java.time.LocalDateTime;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HotDealItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(nullable = false, foreignKey = @ForeignKey(name = "fk_hot_deal_item_site_id"))
  private HotDealSite site;

  @Column(nullable = false, length = 64)
  private String externalId;

  @Column(nullable = false, length = 512)
  private String title;

  @Column(nullable = false, length = 1024)
  private String url;

  @Column(length = 64)
  private String author;

  @Column(nullable = false)
  @Builder.Default
  private int recommendationCount = 0;

  @Column(nullable = false)
  @Builder.Default
  private int unrecommendationCount = 0;

  @Column(nullable = false)
  @Builder.Default
  private int viewCount = 0;

  @Column(nullable = false)
  @Builder.Default
  private int commentCount = 0;

  @Column(length = 128)
  private String price;

  @Column(length = 64)
  private String dealCategory;

  @Column(length = 1024)
  private String thumbnailUrl;

  @Column(nullable = false)
  @Builder.Default
  private boolean notified = false;

  private LocalDateTime notifiedAt;

  @Column(nullable = false)
  private LocalDateTime scrapedAt;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "TIMESTAMP(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "TIMESTAMP(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  public void markNotified() {
    this.notified = true;
    this.notifiedAt = LocalDateTime.now();
  }

  public void updateCounts(int recommendationCount, int unrecommendationCount, int viewCount, int commentCount) {
    this.recommendationCount = recommendationCount;
    this.unrecommendationCount = unrecommendationCount;
    this.viewCount = viewCount;
    this.commentCount = commentCount;
  }

  public void updateThumbnailUrl(String thumbnailUrl) {
    this.thumbnailUrl = thumbnailUrl;
  }
}
