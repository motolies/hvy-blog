package kr.hvy.blog.modules.auth.domain.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RsaKeyResponse {
  private String publicKey;
}
