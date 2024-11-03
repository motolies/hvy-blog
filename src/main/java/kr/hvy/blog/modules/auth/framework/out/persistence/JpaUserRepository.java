package kr.hvy.blog.modules.auth.framework.out.persistence;

import java.util.Optional;
import kr.hvy.blog.modules.auth.framework.out.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaUserRepository extends JpaRepository<UserEntity, Long> {

  Optional<UserEntity> findByUsername(String username);
}
