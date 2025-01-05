package kr.hvy.blog.modules.file.framework.out.entity;


import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import io.hypersistence.tsid.TSID;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import kr.hvy.blog.modules.post.framework.out.entity.PostEntity;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;

@Entity
@Table(name = "`file`")
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class FileEntity {

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

  @ManyToOne(fetch = FetchType.LAZY, targetEntity = PostEntity.class)
  @Cascade({CascadeType.SAVE_UPDATE, CascadeType.LOCK})
  @JoinColumns({@JoinColumn(name = "postId", referencedColumnName = "id", nullable = false)})
  @JsonBackReference("post-files")
  private PostEntity post;

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

  @Transient
  private String resourceUri;

  public String getResourceUri() {
    return "/api/file/" + TSID.from(this.id);
  }

  /*****************************************************************************
   * 연관관계 메소드
   *****************************************************************************/
  public void setPost(PostEntity postEntity) {
    this.post = postEntity;
    postEntity.getFiles().add(this);
  }

}
