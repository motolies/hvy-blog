package kr.hvy.blog.modules.auth.adapter.out;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.hvy.blog.modules.auth.adapter.out.persistence.JpaRsaRepository;
import kr.hvy.blog.modules.auth.adapter.out.redis.RedisRsa;
import kr.hvy.blog.modules.auth.application.port.out.RsaManagementUseCase;
import kr.hvy.blog.modules.common.cache.domain.code.RedisConstant;
import kr.hvy.common.layer.OutputAdapter;
import kr.hvy.common.security.encrypt.RSAEncrypt;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;

@OutputAdapter
@RequiredArgsConstructor
public class RsaManagementAdapter implements RsaManagementUseCase {

  @Qualifier("taskExecutor")
  private final TaskExecutor taskExecutor;
  private final RedisTemplate<String, String> redisTemplateString;
  private final JpaRsaRepository jpaRsaRepository;

  @Override
  public String getRandomRsaPublicKey() {
    long count = ObjectUtils.defaultIfNull(redisTemplateString.opsForSet().size(RedisConstant.RSA_KEY), 0L);
    if (count < 100) {
      generateRsaKey(10);
    }
    return redisTemplateString.opsForSet().randomMember(RedisConstant.RSA_KEY);
  }

  @Override
  public RedisRsa findByPublicKey(String publicKey) {
    return jpaRsaRepository.findById(publicKey)
        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 키입니다."));
  }

  private void generateRsaKey(int count) {
    final List<CompletableFuture> futures = IntStream.rangeClosed(1, count)
        .boxed()
        .map(i -> CompletableFuture.supplyAsync(() -> {
              try {
                KeyPair pair = RSAEncrypt.makeRsaKeyPair();
                RedisRsa redisRsa = RedisRsa.builder()
                    .publicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .privateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))
                    .build();
                jpaRsaRepository.save(redisRsa);
              } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
              }
              return null;
            }, taskExecutor)
        )
        .collect(Collectors.toList());

    futures.stream()
        .map(CompletableFuture::join)
        .toList();
  }


}
