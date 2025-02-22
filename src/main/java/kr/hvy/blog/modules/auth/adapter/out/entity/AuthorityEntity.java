package kr.hvy.blog.modules.auth.adapter.out.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "authority", uniqueConstraints = @UniqueConstraint(name = "uk_authority_name", columnNames = "name"))
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
  @JoinTable(name = "user_authority_map"
      , joinColumns = {@JoinColumn(name = "authorityId", foreignKey = @ForeignKey(name = "fk_user_authority_map_authority_id"))}
      , inverseJoinColumns = {@JoinColumn(name = "userId", foreignKey = @ForeignKey(name = "fk_user_authority_map_user_id"))}
  )
  private Set<UserEntity> users = new HashSet<>();

}