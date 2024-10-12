package kr.hvy.blog.modules.auth.adapter.out.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
import org.apache.commons.collections4.CollectionUtils;

@Entity
@Table(name = "authority")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthorityEntity {

  @Id
  @Tsid
  private Long id;

  @Column(name = "NAME", length = 50, unique = true)
  @Convert(converter = AuthorityNameConverter.class)
  private AuthorityName name;

  @JsonIgnore
  @Builder.Default
  @ManyToMany(targetEntity = UserEntity.class, fetch = FetchType.LAZY)
  @JoinTable(name = "user_authority_map", joinColumns = {@JoinColumn(name = "authority_id")}, inverseJoinColumns = {@JoinColumn(name = "user_id")})
  private Set<UserEntity> users = new HashSet<>();

}