package kr.hvy.blog.modules.auth.adapter.out.persistence;

import kr.hvy.blog.modules.auth.adapter.out.redis.RedisRsa;
import org.springframework.data.repository.CrudRepository;

public interface JpaRsaRepository extends CrudRepository<RedisRsa, String > {

}
