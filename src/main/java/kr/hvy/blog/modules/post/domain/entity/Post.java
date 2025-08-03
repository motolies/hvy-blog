package kr.hvy.blog.modules.post.domain.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import kr.hvy.blog.modules.category.domain.entity.Category;
import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.post.application.dto.PostUpdate;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import kr.hvy.common.security.SecurityUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;
import org.apache.commons.lang3.StringUtils;

@Entity
@Table(name = "post")
@Getter
@Setter
@Builder
@With
@AllArgsConstructor
@NoArgsConstructor
public class Post {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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
  @ManyToMany(mappedBy = "posts", fetch = FetchType.LAZY, targetEntity = Tag.class, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JsonManagedReference("post-tags")
  @Builder.Default
  private Set<Tag> tags = new HashSet<>();

  @OrderBy("originName ASC")
  @OneToMany(mappedBy = "post", fetch = FetchType.LAZY, targetEntity = File.class, cascade = CascadeType.ALL, orphanRemoval = true)
  @JsonManagedReference("post-files")
  @Builder.Default
  private Set<File> files = new HashSet<>();

  @ManyToOne(fetch = FetchType.LAZY, targetEntity = Category.class, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JoinColumn(name = "categoryId", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_post_category_id"))
  private Category category;

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

  @PreRemove
  protected void preRemove() {
    // https://www.baeldung.com/jpa-remove-entity-many-to-many
    // 다대다 관계에서 연관된 엔티티를 삭제할 때는 연관된 엔티티의 컬렉션에서 삭제해야 한다.
    // 복사본을 만들어서 순회해야 null pointer exception이 발생하지 않는다.
    new HashSet<>(this.tags).forEach(this::removeTag);
  }

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  public void update(PostUpdate update, Category category) {
    this.subject = update.getSubject();
    this.body = update.getBody();
    this.publicAccess = update.isPublic();
    this.mainPage = update.isMain();
    this.updated = EventLogEntity.builder()
        .at(LocalDateTime.now())
        .by(SecurityUtils.getUsername())
        .build();

    if (category != null) {
      this.category = category;
    }
  }


  /*****************************************************************************
   * 연관관계 메소드
   *****************************************************************************/
  public void addTag(Tag tag) {
    this.tags.add(tag);
    tag.getPosts().add(this);
  }

  public void removeTag(Tag tag) {
    this.tags.remove(tag);
    tag.getPosts().remove(this);
  }

  public void setCategory(Category category) {
    this.category = category;
    category.getPosts().add(this);
  }


  public void removeFile(File file) {
    this.files.remove(file);
    if (file.getPost() == this) {
      file.setPost(null);
    }
  }

  public void addFile(String originName, String type, String path, long fileSize) {
    File file = File.builder()
        .originName(originName)
        .type(type)
        .path(path)
        .fileSize(fileSize)
        .deleted(false)
        .build();

    this.files.add(file);
    file.setPost(this);
  }
}