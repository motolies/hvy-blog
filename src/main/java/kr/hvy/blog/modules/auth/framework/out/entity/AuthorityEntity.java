package kr.hvy.blog.modules.auth.framework.out.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "authority")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthorityEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "name", length = 50, unique = true)
  @Convert(converter = AuthorityNameConverter.class)
  private AuthorityName name;

  @JsonIgnore
  @Builder.Default
  @ManyToMany(targetEntity = UserEntity.class, fetch = FetchType.LAZY)
  @JoinTable(name = "user_authority_map", joinColumns = {@JoinColumn(name = "authorityId")}, inverseJoinColumns = {@JoinColumn(name = "userId")})
  private Set<UserEntity> users = new HashSet<>();

}