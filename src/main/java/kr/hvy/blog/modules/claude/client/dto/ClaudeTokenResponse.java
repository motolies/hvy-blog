package kr.hvy.blog.modules.claude.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ClaudeTokenResponse {

  @JsonProperty("access_token")
  private String accessToken;

  @JsonProperty("token_type")
  private String tokenType;

  @JsonProperty("expires_in")
  private Long expiresIn;

  @JsonProperty("refresh_token")
  private String refreshToken;
}
