package kr.hvy.blog.modules.auth.repository;

import kr.hvy.blog.modules.auth.domain.RedisRsa;
import org.springframework.data.repository.CrudRepository;

public interface RsaRepository extends CrudRepository<RedisRsa, String > {

}
