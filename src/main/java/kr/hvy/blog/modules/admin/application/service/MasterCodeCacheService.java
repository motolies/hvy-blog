package kr.hvy.blog.modules.admin.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeTreeResponse;
import kr.hvy.blog.modules.common.cache.domain.code.CacheConstant;
import kr.hvy.common.config.jackson.ObjectMapperConfigurer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * 마스터코드 L1(Caffeine) + L2(Redis) 2단계 캐시 서비스
 *
 * 조회 흐름: L1 -> L2 -> DB
 * 무효화 흐름: L1 evict + L2 evict
 *
 * L2(Redis) 저장 시 default typing이 없는 ObjectMapper로 JSON String 직렬화.
 * DTO가 @Value(final class)이므로 Redisson 기본 코덱(NON_FINAL typing)으로는
 * 역직렬화 시 LinkedHashMap으로 복원되어 타입 정보가 손실되는 문제를 방지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterCodeCacheService {

  private final CacheManager cacheManager;
  private final RedissonClient redissonClient;

  private static final ObjectMapper objectMapper = ObjectMapperConfigurer.getObjectMapper();
  private static final TypeReference<List<MasterCodeTreeResponse>> TREE_TYPE_REF = new TypeReference<>() {};
  private static final TypeReference<List<MasterCodeResponse>> CHILDREN_TYPE_REF = new TypeReference<>() {};

  private static final String L2_TREE_MAP = "masterCode:tree";
  private static final String L2_CHILDREN_MAP = "masterCode:children";
  private static final long L2_TTL_HOURS = 1;

  private static final String TREE_FULL_KEY = "full";
  private static final String TREE_ROOT_PREFIX = "root:";

  // ========== 트리 캐시 ==========

  /**
   * 전체 트리 캐시 조회 (L1 -> L2)
   */
  @SuppressWarnings("unchecked")
  public List<MasterCodeTreeResponse> getFullTree() {
    // L1
    Cache l1 = getL1TreeCache();
    if (l1 != null) {
      List<MasterCodeTreeResponse> cached = l1.get(TREE_FULL_KEY, List.class);
      if (cached != null) {
        return cached;
      }
    }

    // L2
    String json = getL2String(getL2TreeMap(), TREE_FULL_KEY);
    List<MasterCodeTreeResponse> l2Cached = deserialize(json, TREE_TYPE_REF);
    if (l2Cached != null) {
      putL1Tree(TREE_FULL_KEY, l2Cached);
      return l2Cached;
    }

    return null;
  }

  /**
   * 전체 트리 캐시 저장 (L1 + L2)
   */
  public void putFullTree(List<MasterCodeTreeResponse> tree) {
    putL1Tree(TREE_FULL_KEY, tree);
    String json = serialize(tree);
    if (json != null) {
      getL2TreeMap().put(TREE_FULL_KEY, json, L2_TTL_HOURS, TimeUnit.HOURS);
    }
  }

  /**
   * 루트 코드별 서브트리 캐시 조회 (L1 -> L2)
   */
  @SuppressWarnings("unchecked")
  public List<MasterCodeTreeResponse> getSubTree(String rootCode) {
    String key = TREE_ROOT_PREFIX + rootCode;

    Cache l1 = getL1TreeCache();
    if (l1 != null) {
      List<MasterCodeTreeResponse> cached = l1.get(key, List.class);
      if (cached != null) {
        return cached;
      }
    }

    String json = getL2String(getL2TreeMap(), key);
    List<MasterCodeTreeResponse> l2Cached = deserialize(json, TREE_TYPE_REF);
    if (l2Cached != null) {
      putL1Tree(key, l2Cached);
      return l2Cached;
    }

    return null;
  }

  /**
   * 루트 코드별 서브트리 캐시 저장
   */
  public void putSubTree(String rootCode, List<MasterCodeTreeResponse> tree) {
    String key = TREE_ROOT_PREFIX + rootCode;
    putL1Tree(key, tree);
    String json = serialize(tree);
    if (json != null) {
      getL2TreeMap().put(key, json, L2_TTL_HOURS, TimeUnit.HOURS);
    }
  }

  // ========== Children 캐시 ==========

  /**
   * 루트 코드의 직계 자식 목록 캐시 조회 (L1 -> L2)
   */
  @SuppressWarnings("unchecked")
  public List<MasterCodeResponse> getChildren(String rootCode) {
    Cache l1 = getL1ChildrenCache();
    if (l1 != null) {
      List<MasterCodeResponse> cached = l1.get(rootCode, List.class);
      if (cached != null) {
        return cached;
      }
    }

    String json = getL2String(getL2ChildrenMap(), rootCode);
    List<MasterCodeResponse> l2Cached = deserialize(json, CHILDREN_TYPE_REF);
    if (l2Cached != null) {
      putL1Children(rootCode, l2Cached);
      return l2Cached;
    }

    return null;
  }

  /**
   * 루트 코드의 직계 자식 목록 캐시 저장
   */
  public void putChildren(String rootCode, List<MasterCodeResponse> children) {
    putL1Children(rootCode, children);
    String json = serialize(children);
    if (json != null) {
      getL2ChildrenMap().put(rootCode, json, L2_TTL_HOURS, TimeUnit.HOURS);
    }
  }

  // ========== 캐시 무효화 ==========

  /**
   * 특정 루트 코드 관련 캐시 무효화 (CUD 시 호출)
   */
  public void evictByRootCode(String rootCode) {
    log.debug("마스터코드 캐시 무효화: rootCode={}", rootCode);

    // L1 evict
    evictL1Tree(TREE_FULL_KEY);
    evictL1Tree(TREE_ROOT_PREFIX + rootCode);
    evictL1Children(rootCode);

    // L2 evict
    getL2TreeMap().remove(TREE_FULL_KEY);
    getL2TreeMap().remove(TREE_ROOT_PREFIX + rootCode);
    getL2ChildrenMap().remove(rootCode);
  }

  /**
   * 전체 캐시 무효화
   */
  public void evictAll() {
    log.debug("마스터코드 전체 캐시 무효화");

    // L1 evict
    Cache treeCache = getL1TreeCache();
    if (treeCache != null) {
      treeCache.clear();
    }
    Cache childrenCache = getL1ChildrenCache();
    if (childrenCache != null) {
      childrenCache.clear();
    }

    // L2 evict
    getL2TreeMap().clear();
    getL2ChildrenMap().clear();
  }

  // ========== L2 직렬화/역직렬화 ==========

  /**
   * L2에서 String 값 조회. 이전 형식(ArrayList 등)이 남아있으면 해당 키를 삭제하고 null 반환.
   */
  private String getL2String(RMapCache<String, String> map, String key) {
    try {
      return map.get(key);
    } catch (ClassCastException e) {
      log.warn("L2 캐시 이전 형식 감지, 키 삭제: map={}, key={}", map.getName(), key);
      map.remove(key);
      return null;
    }
  }

  private <T> String serialize(T value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      log.warn("L2 캐시 직렬화 실패", e);
      return null;
    }
  }

  private <T> T deserialize(String json, TypeReference<T> typeRef) {
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, typeRef);
    } catch (JsonProcessingException e) {
      log.warn("L2 캐시 역직렬화 실패", e);
      return null;
    }
  }

  // ========== 내부 헬퍼 ==========

  private Cache getL1TreeCache() {
    return cacheManager.getCache(CacheConstant.MASTER_CODE_TREE);
  }

  private Cache getL1ChildrenCache() {
    return cacheManager.getCache(CacheConstant.MASTER_CODE_CHILDREN);
  }

  private void putL1Tree(String key, Object value) {
    Cache l1 = getL1TreeCache();
    if (l1 != null) {
      l1.put(key, value);
    }
  }

  private void putL1Children(String key, Object value) {
    Cache l1 = getL1ChildrenCache();
    if (l1 != null) {
      l1.put(key, value);
    }
  }

  private void evictL1Tree(String key) {
    Cache l1 = getL1TreeCache();
    if (l1 != null) {
      l1.evict(key);
    }
  }

  private void evictL1Children(String key) {
    Cache l1 = getL1ChildrenCache();
    if (l1 != null) {
      l1.evict(key);
    }
  }

  private RMapCache<String, String> getL2TreeMap() {
    return redissonClient.getMapCache(L2_TREE_MAP);
  }

  private RMapCache<String, String> getL2ChildrenMap() {
    return redissonClient.getMapCache(L2_CHILDREN_MAP);
  }
}
