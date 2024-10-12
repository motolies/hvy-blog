package kr.hvy.blog.modules.auth.adapter.out.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

@Entity
@Table(name = "users")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity {

  @Id
  @Tsid
  private Long id;

  @Column(nullable = false, length = 128)
  private String name;

  @Column(name = "LoginId", length = 32, unique = true, nullable = false)
  private String username;

  @JsonIgnore
  @Column(length = 64, nullable = false)
  private String password;

  @JsonIgnore
  private Boolean isEnabled;

  @JsonIgnore
  @Builder.Default
  @ManyToMany(mappedBy = "users", targetEntity = AuthorityEntity.class, fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  private Set<AuthorityEntity> authorities = new HashSet<>();

  public void addAuthority(AuthorityEntity authority) {
    CollectionUtils.addIgnoreNull(this.authorities, authority);
    authority.getUsers().add(this);
  }

  public void removeAuthority(AuthorityEntity authority) {
    this.authorities.remove(authority);
    authority.getUsers().remove(this);
  }

}