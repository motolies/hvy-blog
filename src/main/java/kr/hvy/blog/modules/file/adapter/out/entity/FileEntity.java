package kr.hvy.blog.modules.file.adapter.out.entity;


import com.fasterxml.jackson.annotation.*;
import io.hypersistence.tsid.TSID;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import kr.hvy.common.domain.embeddable.EventLogEntity;
import lombok.*;
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

    // todo : 파일이 삭제될 떄 실제 파일도 삭제될 수 있도록 처리, 엔티티 리스너?

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

    @Column(nullable = false, length = 64)
    private String originId;


    @ManyToOne(fetch = FetchType.LAZY, targetEntity = PostEntity.class, cascade = {jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE})
    @JoinColumn(name = "postId", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_file_post"))
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
