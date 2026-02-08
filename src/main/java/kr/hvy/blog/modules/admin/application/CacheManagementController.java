package kr.hvy.blog.modules.admin.application;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import kr.hvy.common.core.exception.DataNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 캐시 관리 컨트롤러
 * 캐시 삭제, 통계 조회 등의 관리 기능 제공
 */
@Slf4j
@RestController
@RequestMapping("/api/cache/admin")
@RequiredArgsConstructor
public class CacheManagementController {

  private final CacheManager cacheManager;

  /**
   * 모든 캐시 삭제
   */
  @PostMapping("/evict-all")
  public Map<String, Object> evictAllCaches() {
    int evictedCount = 0;
    for (String cacheName : cacheManager.getCacheNames()) {
      Cache cache = cacheManager.getCache(cacheName);
      if (ObjectUtils.isNotEmpty(cache)) {
        cache.clear();
        evictedCount++;
        log.info("Cache evicted: {}", cacheName);
      }
    }

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("message", "모든 캐시가 삭제되었습니다.");
    response.put("evictedCacheCount", evictedCount);
    response.put("evictedCacheNames", cacheManager.getCacheNames());

    return response;
  }

  /**
   * 특정 캐시 삭제
   */
  @PostMapping("/evict/{cacheName}")
  public Map<String, Object> evictCache(@PathVariable String cacheName) {
    Cache cache = cacheManager.getCache(cacheName);

    if (ObjectUtils.isEmpty(cache)) {
      throw new DataNotFoundException("error.cache.not.found");
    }

    cache.clear();
    log.info("Cache evicted: {}", cacheName);

    Map<String, Object> response = new HashMap<>();
    response.put("success", true);
    response.put("message", "캐시가 삭제되었습니다: " + cacheName);
    response.put("cacheName", cacheName);

    return response;
  }

  /**
   * 캐시 목록 조회
   */
  @GetMapping("/list")
  public Map<String, Object> listCaches() {
    List<String> cacheNames = cacheManager.getCacheNames().stream()
        .sorted()
        .collect(Collectors.toList());

    Map<String, Object> response = new HashMap<>();
    response.put("cacheNames", cacheNames);
    response.put("totalCount", cacheNames.size());

    return response;
  }

  /**
   * 모든 캐시 통계 조회
   */
  @GetMapping("/stats")
  public Map<String, Object> getAllCacheStats() {
    Map<String, Map<String, Object>> allStats = new HashMap<>();

    for (String cacheName : cacheManager.getCacheNames()) {
      Cache cache = cacheManager.getCache(cacheName);
      if (cache instanceof CaffeineCache) {
        CaffeineCache caffeineCache = (CaffeineCache) cache;
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
            caffeineCache.getNativeCache();
        CacheStats stats = nativeCache.stats();

        Map<String, Object> statsMap = buildStatsMap(stats);
        allStats.put(cacheName, statsMap);
      }
    }

    Map<String, Object> response = new HashMap<>();
    response.put("cacheStats", allStats);
    response.put("totalCacheCount", allStats.size());

    return response;
  }

  /**
   * 특정 캐시 통계 조회
   */
  @GetMapping("/stats/{cacheName}")
  public Map<String, Object> getCacheStats(@PathVariable String cacheName) {
    Cache cache = cacheManager.getCache(cacheName);

    if (ObjectUtils.isEmpty(cache)) {
      throw new DataNotFoundException("error.cache.not.found");
    }

    if (!(cache instanceof CaffeineCache)) {
      throw new IllegalArgumentException("error.cache.not.caffeine");
    }

    CaffeineCache caffeineCache = (CaffeineCache) cache;
    com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
        caffeineCache.getNativeCache();
    CacheStats stats = nativeCache.stats();

    Map<String, Object> response = new HashMap<>();
    response.put("cacheName", cacheName);
    response.put("stats", buildStatsMap(stats));

    return response;
  }

  /**
   * CacheStats를 Map으로 변환
   */
  private Map<String, Object> buildStatsMap(CacheStats stats) {
    Map<String, Object> statsMap = new HashMap<>();
    statsMap.put("hitCount", stats.hitCount());
    statsMap.put("missCount", stats.missCount());
    statsMap.put("hitRate", stats.hitRate());
    statsMap.put("loadSuccessCount", stats.loadSuccessCount());
    statsMap.put("loadFailureCount", stats.loadFailureCount());
    statsMap.put("totalLoadTime", stats.totalLoadTime());
    statsMap.put("averageLoadPenalty", stats.averageLoadPenalty());
    statsMap.put("evictionCount", stats.evictionCount());
    statsMap.put("evictionWeight", stats.evictionWeight());

    // 추가 계산 지표
    long requestCount = stats.requestCount();
    statsMap.put("requestCount", requestCount);
    statsMap.put("missRate", requestCount > 0 ? (double) stats.missCount() / requestCount : 0.0);

    return statsMap;
  }
}
