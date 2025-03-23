package kr.hvy.blog.modules.common.publicip.application.port.out;

import java.util.Optional;
import kr.hvy.blog.modules.common.publicip.domain.PublicIP;

public interface PublicIPManagementPort {
  Optional<PublicIP> getPublicIP();
  void save(String ip);
}
