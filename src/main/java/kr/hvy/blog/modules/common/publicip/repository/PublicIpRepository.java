package kr.hvy.blog.modules.common.publicip.repository;

import kr.hvy.blog.modules.common.publicip.domain.RedisPublicIp;
import org.springframework.data.repository.CrudRepository;


public interface PublicIpRepository extends CrudRepository<RedisPublicIp, String> {

}
