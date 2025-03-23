package kr.hvy.blog.modules.common.publicip.adaptor.out.persistence;

import kr.hvy.blog.modules.common.publicip.adaptor.out.redis.RedisPublicIP;
import org.springframework.data.repository.CrudRepository;


public interface PublicIPRepository extends CrudRepository<RedisPublicIP, String> {

}
