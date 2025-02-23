package kr.hvy.blog.modules.post.adapter.out.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import kr.hvy.blog.modules.category.adapter.out.entity.CategoryEntity;
import kr.hvy.blog.modules.file.adapter.out.entity.FileEntity;
import kr.hvy.blog.modules.post.domain.code.PostStatus;
import kr.hvy.blog.modules.tag.adapter.out.entity.TagEntity;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;

@Entity
@Table(name = "post"
    , uniqueConstraints = @UniqueConstraint(name = "uk_post_id_status", columnNames = {"id", "status"}))
@Getter
@Setter
@Builder
@With
@AllArgsConstructor
@NoArgsConstructor
public class PostEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Convert(converter = PostStatusConverter.class)
  @Column(nullable = false, columnDefinition = "CHAR(3)")
  private PostStatus status;

  @Column(nullable = false, length = 512)
  private String subject;

  @Column(nullable = false, columnDefinition = "LONGTEXT")
  private String body;

  @Column(nullable = false, columnDefinition = "LONGTEXT")
  private String normalBody;

  @Column(nullable = false)
  private boolean publicAccess;

  @Column(nullable = false)
  private boolean mainPage;

  @Column(nullable = false)
  private int viewCount;

  @OrderBy("name ASC")
  @ManyToMany(mappedBy = "posts", fetch = FetchType.LAZY, targetEntity = TagEntity.class)
  @Cascade({CascadeType.SAVE_UPDATE, CascadeType.LOCK})
  @JsonManagedReference("post-tags")
  @Builder.Default
  private Set<TagEntity> tags = new HashSet<>();

  // todo : 나중에 파일 삭제시에 실제 파일도 삭제할 수 있도록 변경 예쩡 (cascade = CascadeType.ALL)
  @OrderBy("originName ASC")
  @OneToMany(mappedBy = "post", fetch = FetchType.LAZY, targetEntity = FileEntity.class)
  @Cascade({CascadeType.SAVE_UPDATE, CascadeType.LOCK})
  @JsonManagedReference("post-files")
  @Builder.Default
  private Set<FileEntity> files = new HashSet<>();

  @ManyToOne(fetch = FetchType.LAZY, targetEntity = CategoryEntity.class)
  @JoinColumn(name = "categoryId", referencedColumnName = "id", nullable = false, foreignKey = @jakarta.persistence.ForeignKey(name = "fk_post_category_id"))
  private CategoryEntity category;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "DATETIME(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", columnDefinition = "DATETIME(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();

  @PrePersist
  @PreUpdate
  protected void pretreatment() {
    if (StringUtils.isBlank(this.body)) {
      this.normalBody = this.body = "";
    } else {
      this.normalBody = body.replaceAll("<[^>]*>", "");
    }
  }


  /*****************************************************************************
   * 연관관계 메소드
   *****************************************************************************/
  public void addTag(TagEntity tagEntity) {
    this.tags.add(tagEntity);
    tagEntity.getPosts().add(this);
  }

  public void removeTag(TagEntity tagEntity) {
    this.tags.remove(tagEntity);
    tagEntity.getPosts().remove(this);
  }

  public void setCategory(CategoryEntity category) {
    this.category = category;
    category.getPosts().add(this);
  }
}