package kr.hvy.blog.modules.auth.application.port.out;

import kr.hvy.blog.modules.auth.adapter.out.redis.RedisRsa;

public interface RsaManagementUseCase {

  String getRandomRsaPublicKey();

  RedisRsa findByPublicKey(String publicKey);

}
