package kr.hvy.blog.modules.memo.domain.entity;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.hypersistence.tsid.TSID;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import kr.hvy.common.core.security.SecurityUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;

@Entity
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class Memo {

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

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "categoryId", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_memo_category"))
  private MemoCategory category;

  @Column(nullable = false)
  @Builder.Default
  private boolean deleted = false;

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

  public void update(String content, MemoCategory category) {
    this.content = content;
    this.category = category;
    this.updated = EventLogEntity.builder()
        .at(LocalDateTime.now())
        .by(SecurityUtils.getUsername())
        .build();
  }

  public void softDelete() {
    this.deleted = true;
    this.updated = EventLogEntity.builder()
        .at(LocalDateTime.now())
        .by(SecurityUtils.getUsername())
        .build();
  }
}
