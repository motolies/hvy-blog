package kr.hvy.blog.modules.common.publicip.domain;

import java.sql.Timestamp;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Value
@Builder
@With
public class PublicIP {

  String id;
  String ip;
  Timestamp created;
}
