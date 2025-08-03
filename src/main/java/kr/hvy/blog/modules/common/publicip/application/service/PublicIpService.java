package kr.hvy.blog.modules.common.publicip.application.service;

import java.util.Optional;
import kr.hvy.blog.modules.common.publicip.domain.RedisPublicIp;
import kr.hvy.blog.modules.common.publicip.repository.PublicIpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PublicIpService {

  private final PublicIpRepository publicIPRepository;
  private static final String REDIS_PUBLIC_IP_KEY = "CURRENT_PUBLIC_IP";

  public Optional<RedisPublicIp> getPublicIP() {
    return publicIPRepository.findById(REDIS_PUBLIC_IP_KEY);
  }

  public void save(String publicIp) {
    publicIPRepository.save(RedisPublicIp.builder()
        .id(REDIS_PUBLIC_IP_KEY)
        .ip(publicIp)
        .build());
  }
}
