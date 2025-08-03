package kr.hvy.blog.modules.auth.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

@Entity
@Table(name = "user", uniqueConstraints = @UniqueConstraint(name = "uk_user_login_id", columnNames = "loginId"))
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 128)
  private String name;

  @Column(name = "loginId", length = 32, unique = true, nullable = false, updatable = false)
  private String username;

  @JsonIgnore
  @Column(length = 64, nullable = false)
  private String password;

  @JsonIgnore
  private Boolean isEnabled;

  @JsonIgnore
  @Builder.Default
  @ManyToMany(mappedBy = "users", targetEntity = Authority.class, fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  private Set<Authority> authorities = new HashSet<>();

  public void addAuthority(Authority authority) {
    CollectionUtils.addIgnoreNull(this.authorities, authority);
    authority.getUsers().add(this);
  }

  public void removeAuthority(Authority authority) {
    this.authorities.remove(authority);
    authority.getUsers().remove(this);
  }

}