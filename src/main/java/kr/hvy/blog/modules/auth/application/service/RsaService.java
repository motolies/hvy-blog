package kr.hvy.blog.modules.auth.application.service;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.hvy.blog.modules.auth.domain.RedisRsa;
import kr.hvy.blog.modules.auth.repository.RsaRepository;
import kr.hvy.blog.modules.common.cache.domain.code.RedisConstant;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.security.encrypt.RSAEncrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RsaService {

  private static final int RSA_KEY_POOL_MINIMUM_SIZE = 100;
  private static final int RSA_KEY_GENERATION_BATCH_SIZE = 10;

  @Qualifier("taskExecutor")
  private final TaskExecutor taskExecutor;
  private final RedisTemplate<String, String> redisTemplate;
  private final RsaRepository rsaRepository;

  public String getRandomRsaPublicKey() {
    long currentKeyCount = getCurrentRsaKeyCount();
    if (currentKeyCount < RSA_KEY_POOL_MINIMUM_SIZE) {
      generateRsaKeysAsync(RSA_KEY_GENERATION_BATCH_SIZE);
    }
    return redisTemplate.opsForSet().randomMember(RedisConstant.RSA_KEY);
  }

  public RedisRsa findByPublicKey(String publicKey) {
    return rsaRepository.findById(publicKey)
        .orElseThrow(() -> new DataNotFoundException("존재하지 않는 RSA 공개키입니다: " + publicKey));
  }

  private long getCurrentRsaKeyCount() {
    return ObjectUtils.defaultIfNull(redisTemplate.opsForSet().size(RedisConstant.RSA_KEY), 0L);
  }

  private void generateRsaKeysAsync(int count) {
    final List<CompletableFuture<Void>> rsaKeyGenerationFutures = IntStream.rangeClosed(1, count)
        .boxed()
        .map(i -> CompletableFuture.runAsync(this::generateSingleRsaKey, taskExecutor))
        .toList();

    // 모든 비동기 작업 완료 대기
    CompletableFuture.allOf(rsaKeyGenerationFutures.toArray(new CompletableFuture[0]))
        .exceptionally(throwable -> {
          log.error("RSA 키 생성 중 일부 작업이 실패했습니다.", throwable);
          return null;
        });
  }

  private void generateSingleRsaKey() {
    try {
      KeyPair keyPair = RSAEncrypt.makeRsaKeyPair();
      RedisRsa redisRsa = createRedisRsaFromKeyPair(keyPair);
      rsaRepository.save(redisRsa);
      log.debug("RSA 키가 성공적으로 생성되고 저장되었습니다.");
    } catch (NoSuchAlgorithmException e) {
      log.error("RSA 키 생성 중 알고리즘 오류가 발생했습니다.", e);
      throw new RuntimeException("RSA 키 생성에 실패했습니다.", e);
    }
  }

  private RedisRsa createRedisRsaFromKeyPair(KeyPair keyPair) {
    String encodedPublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    String encodedPrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

    return RedisRsa.builder()
        .publicKey(encodedPublicKey)
        .privateKey(encodedPrivateKey)
        .build();
  }


}
