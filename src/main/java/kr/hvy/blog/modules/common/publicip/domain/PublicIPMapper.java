package kr.hvy.blog.modules.common.publicip.domain;

import kr.hvy.blog.modules.common.publicip.adaptor.out.redis.RedisPublicIP;
import org.mapstruct.Mapper;
import org.mapstruct.ObjectFactory;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PublicIPMapper {

  PublicIPMapper INSTANCE = Mappers.getMapper(PublicIPMapper.class);

  PublicIP toDomain(RedisPublicIP redisPublicIP);

  @ObjectFactory
  default PublicIP.PublicIPBuilder createPublicIPBuilder() {
    return PublicIP.builder();
  }

}
