package kr.hvy.blog.modules.auth.application.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class LoginRequest {

  String username;
  String password;
  String publicKey;
}
