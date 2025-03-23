package kr.hvy.blog.modules.common.publicip.adaptor.out;

import java.util.Optional;
import kr.hvy.blog.modules.common.publicip.adaptor.out.persistence.PublicIPRepository;
import kr.hvy.blog.modules.common.publicip.adaptor.out.redis.RedisPublicIP;
import kr.hvy.blog.modules.common.publicip.application.port.out.PublicIPManagementPort;
import kr.hvy.blog.modules.common.publicip.domain.PublicIP;
import kr.hvy.blog.modules.common.publicip.domain.PublicIPMapper;
import kr.hvy.common.layer.OutputAdapter;
import lombok.RequiredArgsConstructor;

@OutputAdapter
@RequiredArgsConstructor
public class PublicIPManagementAdapter implements PublicIPManagementPort {

  private final PublicIPMapper publicIPMapper;
  private final PublicIPRepository publicIPRepository;
  private static final String REDIS_PUBLIC_IP_KEY = "CURRENT_PUBLIC_IP";

  @Override
  public Optional<PublicIP> getPublicIP() {
    return publicIPRepository.findById(REDIS_PUBLIC_IP_KEY)
        .map(publicIPMapper::toDomain);
  }

  @Override
  public void save(String publicIP) {
    publicIPRepository.save(RedisPublicIP.builder()
        .id(REDIS_PUBLIC_IP_KEY)
        .ip(publicIP)
        .build());
  }
}
