package kr.hvy.blog.modules.auth.framework.out.persistence;

import java.util.Optional;
import kr.hvy.blog.modules.auth.framework.out.entity.AuthorityEntity;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAuthorityRepository extends JpaRepository<AuthorityEntity, Long> {

  Optional<AuthorityEntity> findByName(AuthorityName name);
}
