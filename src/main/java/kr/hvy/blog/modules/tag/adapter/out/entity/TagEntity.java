package kr.hvy.blog.modules.tag.adapter.out.entity;


import com.fasterxml.jackson.annotation.JsonBackReference;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;
import org.hibernate.annotations.Formula;

@Entity
@Table(name = "`tag`", uniqueConstraints = @UniqueConstraint(name = "uk_tag_name", columnNames = "name"))
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class TagEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64, unique = true)
  private String name;


  @ManyToMany(fetch = FetchType.LAZY, targetEntity = PostEntity.class)
  @Cascade({CascadeType.SAVE_UPDATE, CascadeType.LOCK})
  @JoinTable(
      name = "post_tag_map",
      joinColumns = @JoinColumn(name = "tagId", foreignKey = @ForeignKey(name = "fk_post_tag_map_tag_id")),
      inverseJoinColumns = @JoinColumn(name = "postId", foreignKey = @ForeignKey(name = "fk_post_tag_map_post_id"))
  )
  @JsonBackReference("post-tags")
  @Builder.Default
  private Set<PostEntity> posts = new HashSet<>();

  @Formula("(select count(*) from tb_post_tag_map as m where m.tagId = id)")
  private int postCount;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", columnDefinition = "DATETIME(6)", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  /*****************************************************************************
   * 연관관계 메소드
   *****************************************************************************/
  public void addPost(PostEntity postEntity) {
    this.posts.add(postEntity);
    postEntity.getTags().add(this);
  }

  public void removePost(PostEntity postEntity) {
    this.posts.remove(postEntity);
    postEntity.getTags().remove(this);
  }

}
