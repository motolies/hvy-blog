package kr.hvy.blog.modules.auth.application.port.out;

import kr.hvy.blog.modules.auth.domain.User;

public interface UserManagementPort {

  User findByUsername(String username);

  User create(User user);

}
