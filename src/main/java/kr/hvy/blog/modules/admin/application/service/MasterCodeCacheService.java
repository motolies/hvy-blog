package kr.hvy.blog.modules.admin.application.service;

import java.util.List;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeResponse;
import kr.hvy.blog.modules.admin.application.dto.MasterCodeTreeResponse;
import kr.hvy.blog.modules.common.cache.domain.code.CacheConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * 마스터코드 캐시 서비스
 * TwoTierCache(L1 Caffeine + L2 Redis)에 위임합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterCodeCacheService {

  private final CacheManager cacheManager;

  private static final String TREE_FULL_KEY = "full";
  private static final String TREE_ROOT_PREFIX = "root:";

  // ========== 트리 캐시 ==========

  @SuppressWarnings("unchecked")
  public List<MasterCodeTreeResponse> getFullTree() {
    Cache cache = getTreeCache();
    Cache.ValueWrapper wrapper = cache.get(TREE_FULL_KEY);
    return wrapper != null ? (List<MasterCodeTreeResponse>) wrapper.get() : null;
  }

  public void putFullTree(List<MasterCodeTreeResponse> tree) {
    getTreeCache().put(TREE_FULL_KEY, tree);
  }

  @SuppressWarnings("unchecked")
  public List<MasterCodeTreeResponse> getSubTree(String rootCode) {
    Cache cache = getTreeCache();
    Cache.ValueWrapper wrapper = cache.get(TREE_ROOT_PREFIX + rootCode);
    return wrapper != null ? (List<MasterCodeTreeResponse>) wrapper.get() : null;
  }

  public void putSubTree(String rootCode, List<MasterCodeTreeResponse> tree) {
    getTreeCache().put(TREE_ROOT_PREFIX + rootCode, tree);
  }

  // ========== Children 캐시 ==========

  @SuppressWarnings("unchecked")
  public List<MasterCodeResponse> getChildren(String rootCode) {
    Cache cache = getChildrenCache();
    Cache.ValueWrapper wrapper = cache.get(rootCode);
    return wrapper != null ? (List<MasterCodeResponse>) wrapper.get() : null;
  }

  public void putChildren(String rootCode, List<MasterCodeResponse> children) {
    getChildrenCache().put(rootCode, children);
  }

  // ========== 캐시 무효화 ==========

  public void evictByRootCode(String rootCode) {
    log.debug("마스터코드 캐시 무효화: rootCode={}", rootCode);
    getTreeCache().evict(TREE_FULL_KEY);
    getTreeCache().evict(TREE_ROOT_PREFIX + rootCode);
    getChildrenCache().evict(rootCode);
  }

  public void evictAll() {
    log.debug("마스터코드 전체 캐시 무효화");
    getTreeCache().clear();
    getChildrenCache().clear();
  }

  // ========== 내부 헬퍼 ==========

  private Cache getTreeCache() {
    return cacheManager.getCache(CacheConstant.MASTER_CODE_TREE);
  }

  private Cache getChildrenCache() {
    return cacheManager.getCache(CacheConstant.MASTER_CODE_CHILDREN);
  }
}
