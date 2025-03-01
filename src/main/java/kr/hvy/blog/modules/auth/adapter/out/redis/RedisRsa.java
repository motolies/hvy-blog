package kr.hvy.blog.modules.auth.adapter.out.redis;

import org.springframework.data.annotation.Id;
import kr.hvy.blog.modules.common.RedisConstant;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.redis.core.RedisHash;

@Data
@Builder
@RedisHash(value = RedisConstant.RSA_KEY)
public class RedisRsa {
  // todo : 나중에 레디스 공통화
  @Id
  private String publicKey;
  private String privateKey;


}
