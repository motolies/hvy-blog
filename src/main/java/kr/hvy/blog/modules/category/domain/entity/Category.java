package kr.hvy.blog.modules.category.domain.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.hvy.blog.modules.category.application.dto.CategoryUpdate;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.common.core.converter.TsidUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.With;
import org.hibernate.annotations.Formula;
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
public class Category {


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
  @ManyToOne(targetEntity = Category.class, fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JoinColumn(name = "parentId", referencedColumnName = "Id", nullable = true, insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_parent_id"))
  private Category parent;

  @JsonIgnore
  @Builder.Default
  @OneToMany(mappedBy = "category", targetEntity = Post.class, fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  private Set<Post> posts = new HashSet<>();


  @JsonManagedReference
  @OrderBy("seq ASC, name ASC")
  @Builder.Default
  @OneToMany(mappedBy = "parent", targetEntity = Category.class, fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  private List<Category> categories = new ArrayList<>();

  @Formula("(select count(*) from tb_post as p where p.categoryId = id)")
  private int postCount;

  @PrePersist
  private void prePersist() {
    this.id = TsidUtils.getTsid().toString();
  }

  public void update(CategoryUpdate categoryUpdate){
    this.name = categoryUpdate.getName();
    this.parentId = categoryUpdate.getParentId();
  }

}