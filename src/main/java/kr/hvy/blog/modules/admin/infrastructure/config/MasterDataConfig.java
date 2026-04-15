package kr.hvy.blog.modules.admin.infrastructure.config;

import kr.hvy.blog.modules.admin.infrastructure.cache.JpaMasterCodeLoader;
import kr.hvy.blog.modules.admin.mapper.MasterCodeDtoMapper;
import kr.hvy.blog.modules.admin.repository.MasterCodeRepository;
import kr.hvy.common.infrastructure.redis.impl.masterdata.config.MasterDataCommonConfig;
import kr.hvy.common.infrastructure.redis.impl.masterdata.query.MasterCodeLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * blog-back 의 마스터코드 조회 스택 설정.
 * <p>
 * {@link MasterDataCommonConfig} 를 import 하여 공용 {@code MasterCodeCacheService} /
 * {@code MasterCodeQuery} 빈을 활성화하고, 이 모듈 전용으로 {@link JpaMasterCodeLoader} 를 제공한다.
 */
@Configuration
@Import(MasterDataCommonConfig.class)
public class MasterDataConfig {

  @Bean
  public MasterCodeLoader masterCodeLoader(
      MasterCodeRepository masterCodeRepository,
      MasterCodeDtoMapper masterCodeDtoMapper) {
    return new JpaMasterCodeLoader(masterCodeRepository, masterCodeDtoMapper);
  }
}
