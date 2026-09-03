package kr.hvy.blog.modules.stock.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import kr.hvy.blog.modules.stock.domain.code.CollectJobType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 체크포인트 복합키 (job_type, target_key).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectCheckpointId implements Serializable {

  @Column(nullable = false, length = 40)
  private String jobType;

  @Column(nullable = false, length = 20)
  private String targetKey;

  /**
   * enum 잡 유형과 대상 코드로 키를 만든다.
   */
  public static CollectCheckpointId of(CollectJobType jobType, String targetKey) {
    return new CollectCheckpointId(jobType.getCode(), targetKey);
  }
}
