package kr.hvy.blog.modules.file.domain.entity;


import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.hypersistence.tsid.TSID;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

@Entity
@EntityListeners(FileEntityListener.class)
@Table(name = "`file`")
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class File {

  @Id
  @Tsid
  private Long id;

  @JsonGetter("id")
  public String getHexId() {
    return TSID.from(this.id).toString();
  }

  @JsonSetter("id")
  public void setHexId(String id) {
    this.id = TSID.from(id).toLong();
  }

  // post 없이 저장하거나, file 저장시 post를 저장하지 않으므로 CascadeType.PERSIST 제거
  @ManyToOne(fetch = FetchType.LAZY, targetEntity = Post.class, cascade = {CascadeType.MERGE})
  @JoinColumn(name = "postId", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_file_post"))
  @JsonBackReference("post-files")
  private Post post;

  @Column(nullable = false, length = 256)
  private String originName;

  @Column(nullable = false, length = 512)
  private String type;

  @JsonIgnore
  @Column(nullable = false, length = 512)
  private String path;

  @Column(nullable = false, columnDefinition = "BIGINT")
  private long fileSize;

  @JsonProperty("isDelete")
  @Column(nullable = false, length = 1)
  private boolean deleted;

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
  public void setPost(Post post) {
    this.post = post;
    post.getFiles().add(this);
  }

}