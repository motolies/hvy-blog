package kr.hvy.blog.modules.category.adapter.out.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.hvy.blog.modules.post.adapter.out.entity.PostEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

@Entity
@Table(name = "`category`")
@EntityListeners(CategoryEntityListener.class)
@Getter
@Setter
@Builder
@With
@NoArgsConstructor
@AllArgsConstructor
public class CategoryEntity {


  @Id
  @Column(nullable = false, length = 32)
  private String id;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(nullable = false, length = 11)
  private int seq;

  @Column(nullable = false, length = 512)
  private String fullName;

  @Column(nullable = false, length = 512)
  private String fullPath;

  @Column(name = "parentId", columnDefinition = "VARCHAR(32)")
  @GenericGenerator(name = "CATEGORY_PID_CATEGORYID_GENERATOR", strategy = "foreign", parameters = @Parameter(name = "property", value = "parent"))
  private String parentId;

  @JsonBackReference
  @ManyToOne(targetEntity = CategoryEntity.class, fetch = FetchType.LAZY)
  @org.hibernate.annotations.Cascade({org.hibernate.annotations.CascadeType.LOCK})
  @JoinColumn(name = "parentId", referencedColumnName = "Id", nullable = true, insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_parent_id"))
  private CategoryEntity parent;

  @JsonIgnore
  @OneToMany(mappedBy = "category", targetEntity = PostEntity.class, fetch = FetchType.LAZY)
  @Cascade({CascadeType.SAVE_UPDATE, CascadeType.LOCK})
  private Set<PostEntity> posts = new HashSet<>();


  @JsonManagedReference
  @OrderBy("seq ASC, name ASC")
  @OneToMany(mappedBy = "parent", targetEntity = CategoryEntity.class, fetch = FetchType.LAZY)
  @Cascade({CascadeType.SAVE_UPDATE, CascadeType.LOCK})
  private List<CategoryEntity> categories = new ArrayList<>();


}
