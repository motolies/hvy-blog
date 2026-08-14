package kr.hvy.blog.modules.hotdeal.application.service;

import java.util.List;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordCreate;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordResponse;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealKeywordUpdate;
import kr.hvy.blog.modules.hotdeal.application.specification.HotDealKeywordSpecification;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import kr.hvy.blog.modules.hotdeal.repository.HotDealKeywordRepository;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import kr.hvy.common.core.specification.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HotDealKeywordService {

  private final HotDealKeywordRepository keywordRepository;

  @Transactional(readOnly = true)
  public List<HotDealKeywordResponse> getAllKeywords() {
    return keywordRepository.findAllByOrderByKeywordAsc().stream()
        .map(this::toResponse)
        .toList();
  }

  public HotDealKeywordResponse create(HotDealKeywordCreate request) {
    Specification.validate(HotDealKeywordSpecification::new, request.getKeyword());

    // 정규화 기준으로 중복을 검사해야 "그래픽 카드"와 "그래픽카드"가 같은 키워드로 걸린다
    String normalized = HotDealKeyword.normalize(request.getKeyword());
    if (keywordRepository.existsByNormalizedKeyword(normalized)) {
      throw new SpecificationException(
          String.format("이미 등록된 키워드입니다: %s", request.getKeyword()));
    }

    HotDealKeyword saved = keywordRepository.save(
        HotDealKeyword.create(request.getKeyword(), request.isEnabled()));

    log.info("핫딜 키워드 등록: id={}, keyword={}, normalized={}",
        saved.getId(), saved.getKeyword(), saved.getNormalizedKeyword());
    return toResponse(saved);
  }

  public HotDealKeywordResponse update(Long id, HotDealKeywordUpdate request) {
    Specification.validate(HotDealKeywordSpecification::new, request.getKeyword());

    HotDealKeyword keyword = keywordRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("키워드를 찾을 수 없습니다: " + id));

    // 자기 자신은 중복 대상에서 제외한다 (활성 토글만 바꿔도 걸리는 것을 막는다)
    String normalized = HotDealKeyword.normalize(request.getKeyword());
    if (keywordRepository.existsByNormalizedKeywordAndIdNot(normalized, id)) {
      throw new SpecificationException(
          String.format("이미 등록된 키워드입니다: %s", request.getKeyword()));
    }

    keyword.update(request.getKeyword(), request.isEnabled());

    log.info("핫딜 키워드 수정: id={}, keyword={}, enabled={}",
        keyword.getId(), keyword.getKeyword(), keyword.isEnabled());
    return toResponse(keyword);
  }

  public DeleteResponse<Long> delete(Long id) {
    if (!keywordRepository.existsById(id)) {
      throw new DataNotFoundException("키워드를 찾을 수 없습니다: " + id);
    }
    keywordRepository.deleteById(id);

    log.info("핫딜 키워드 삭제: id={}", id);
    return DeleteResponse.<Long>builder().id(id).build();
  }

  private HotDealKeywordResponse toResponse(HotDealKeyword entity) {
    return HotDealKeywordResponse.builder()
        .id(entity.getId())
        .keyword(entity.getKeyword())
        .normalizedKeyword(entity.getNormalizedKeyword())
        .enabled(entity.isEnabled())
        .createdAt(entity.getCreated().getAt())
        .updatedAt(entity.getUpdated().getAt())
        .build();
  }
}
