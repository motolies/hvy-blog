package kr.hvy.blog.modules.series.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.hvy.blog.modules.post.domain.entity.Post;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_series_post", columnNames = {"seriesId", "postId"}))
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SeriesPost {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "seriesId", nullable = false, foreignKey = @ForeignKey(name = "fk_series_post_series_id"))
  private Series series;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "postId", nullable = false, foreignKey = @ForeignKey(name = "fk_series_post_post_id"))
  private Post post;

  @Column(nullable = false)
  private int seq;
}
