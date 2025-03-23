package kr.hvy.blog.modules.common.publicip.adaptor.out.redis;

import jakarta.persistence.PrePersist;
import java.sql.Timestamp;
import kr.hvy.blog.modules.common.cache.domain.code.RedisConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@RedisHash(value = RedisConstant.PUBLIC_IP_KEY)
public class RedisPublicIP {

  // id는 current로 고정해서 사용할 예정
  @Id
  private String id;
  @NonNull
  private String ip;
  private Timestamp created;

  @PrePersist
  public void prePersist() {
    if (this.created == null) {
      this.created = new Timestamp(System.currentTimeMillis());
    }
  }
}
