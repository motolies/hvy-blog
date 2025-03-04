package kr.hvy.blog.modules.auth.application.port.in;

import kr.hvy.blog.modules.auth.domain.User;
import kr.hvy.blog.modules.auth.domain.dto.LoginRequest;
import kr.hvy.blog.modules.auth.domain.dto.RsaKeyResponse;
import kr.hvy.blog.modules.auth.domain.dto.UserCreate;
import kr.hvy.blog.modules.auth.domain.dto.UserResponse;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.domain.usecase.CrudUseCase;


public interface UserManagementUseCase extends CrudUseCase<User, UserResponse, UserCreate, Void, Long, DeleteResponse<Long>> {

  UserResponse login(LoginRequest loginRequest);

  String makeToken(UserResponse userResponse);

  RsaKeyResponse getRsaKey();

  UserResponse getProfile();
}
