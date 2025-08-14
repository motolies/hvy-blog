package kr.hvy.blog.modules.auth.repository;

import java.util.Optional;
import kr.hvy.blog.modules.auth.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUsername(String username);
}
