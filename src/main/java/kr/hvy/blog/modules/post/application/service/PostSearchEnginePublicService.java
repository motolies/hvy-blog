package kr.hvy.blog.modules.post.application.service;

import java.util.List;
import kr.hvy.blog.modules.common.cache.domain.code.CacheConstant;
import kr.hvy.blog.modules.post.application.dto.PostSearchEngineResponse;
import kr.hvy.blog.modules.post.mapper.PostSearchEngineDtoMapper;
import kr.hvy.blog.modules.post.repository.SearchEngineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PostSearchEnginePublicService {

  private final SearchEngineRepository searchEngineRepository;
  private final PostSearchEngineDtoMapper postSearchEngineDtoMapper;

  @Cacheable(cacheNames = CacheConstant.SEARCH_ENGINE)
  public List<PostSearchEngineResponse> findAll() {
    return searchEngineRepository.findAll().stream()
        .map(postSearchEngineDtoMapper::toResponse)
        .toList();
  }

}
