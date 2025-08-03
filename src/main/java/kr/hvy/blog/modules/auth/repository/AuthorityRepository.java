package kr.hvy.blog.modules.auth.repository;

import java.util.Optional;
import kr.hvy.blog.modules.auth.domain.entity.Authority;
import kr.hvy.blog.modules.auth.domain.code.AuthorityName;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorityRepository extends JpaRepository<Authority, Long> {

  Optional<Authority> findByName(AuthorityName name);
}
