package kr.hvy.blog.modules.hotdeal.application.service;

import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealSiteResponse;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealSiteUpdateRequest;
import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.application.filter.DealNotificationFilter;
import kr.hvy.blog.modules.hotdeal.application.filter.DealNotificationFilterResolver;
import kr.hvy.blog.modules.hotdeal.application.matcher.HotDealKeywordMatcher;
import kr.hvy.blog.modules.hotdeal.application.matcher.KeywordMatchResult;
import kr.hvy.blog.modules.hotdeal.application.specification.MinViewCountSpecification;
import kr.hvy.blog.modules.hotdeal.application.specification.RecommendationRatioSpecification;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraper;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraperResolver;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealItem;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import kr.hvy.blog.modules.hotdeal.repository.HotDealItemRepository;
import kr.hvy.blog.modules.hotdeal.repository.HotDealKeywordRepository;
import kr.hvy.blog.modules.hotdeal.repository.HotDealSiteRepository;
import kr.hvy.common.core.specification.Specification;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.message.HotDealMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HotDealService {

  private final HotDealSiteRepository siteRepository;
  private final HotDealItemRepository itemRepository;
  private final HotDealKeywordRepository keywordRepository;
  private final DealSiteScraperResolver scraperResolver;
  private final DealNotificationFilterResolver filterResolver;
  private final Notify notify;

  /**
   * 활성화된 모든 사이트를 스크래핑하고 알림 조건 충족 시 Slack 알림 전송.
   * 사이트별 에러 격리: 한 사이트가 실패해도 나머지 사이트는 계속 처리.
   *
   * <p>알림 조건은 등록 키워드 매칭 OR 사이트별 임계값이다.
   * 키워드에 매칭되면 임계값을 우회하고 @channel 멘션이 붙는다.
   */
  public void scrapeAndNotify() {
    List<HotDealSite> enabledSites = siteRepository.findByEnabledTrue();

    // 키워드는 실행당 1회만 로드하고 정규화한다 (딜 1건마다 조회하면 수백 회 쿼리가 발생)
    HotDealKeywordMatcher keywordMatcher =
        HotDealKeywordMatcher.of(keywordRepository.findByEnabledTrueOrderByKeywordAsc());

    log.info("핫딜 스크래핑 시작: 활성 사이트 수={}, 활성 키워드 수={}",
        enabledSites.size(), keywordMatcher.size());

    for (HotDealSite site : enabledSites) {
      try {
        processSite(site, keywordMatcher);
      } catch (Exception e) {
        log.error("사이트 처리 중 오류 (다음 사이트 계속 진행): siteCode={}, error={}",
            site.getSiteCode(), e.getMessage(), e);
      }
    }
  }

  private void processSite(HotDealSite site, HotDealKeywordMatcher keywordMatcher) {
    DealSiteScraper scraper = scraperResolver.resolve(site.getSiteCode());
    if (scraper == null) {
      log.debug("스크래퍼 없음, 건너뜀: siteCode={}", site.getSiteCode());
      return;
    }

    Specification<ScrapedDeal> notificationSpec = resolveNotificationSpec(site);

    log.info("사이트 스크래핑 시작: siteCode={}", site.getSiteCode());
    List<ScrapedDeal> deals = scraper.scrape(site);
    log.info("사이트 스크래핑 완료: siteCode={}, 수집건수={}", site.getSiteCode(), deals.size());

    int newCount = 0;
    int updatedCount = 0;
    int notifiedCount = 0;
    int keywordNotifiedCount = 0;
    int skippedCount = 0;

    for (ScrapedDeal deal : deals) {
      // 키워드 매칭이 먼저다. 통과하면 임계값 평가를 건너뛴다 (키워드는 임계값을 우회한다)
      KeywordMatchResult keywordMatch = keywordMatcher.match(deal.getTitle());
      boolean shouldNotify = keywordMatch.matched() || notificationSpec.isSatisfiedBy(deal);

      Optional<HotDealItem> existing = itemRepository.findBySiteAndExternalId(site, deal.getExternalId());

      if (existing.isPresent()) {
        HotDealItem item = existing.get();
        item.updateCounts(
            deal.getRecommendationCount(),
            deal.getUnrecommendationCount(),
            deal.getViewCount(),
            deal.getCommentCount());
        item.updateThumbnailUrl(deal.getThumbnailUrl());

        // 이미 알림이 나간 건은 키워드에 매칭돼도 재알림하지 않는다 (중복 @channel 방지)
        if (!item.isNotified() && shouldNotify) {
          sendNotification(site, item, keywordMatch);
          item.markNotified();
          notifiedCount++;
          if (keywordMatch.matched()) {
            keywordNotifiedCount++;
          }
        }
        updatedCount++;
      } else {
        // 키워드에 매칭되면 임계값 미달이어도 저장하고 알림한다
        if (shouldNotify) {
          HotDealItem item = HotDealItem.builder()
              .site(site)
              .externalId(deal.getExternalId())
              .title(deal.getTitle())
              .url(deal.getUrl())
              .author(deal.getAuthor())
              .recommendationCount(deal.getRecommendationCount())
              .unrecommendationCount(deal.getUnrecommendationCount())
              .viewCount(deal.getViewCount())
              .commentCount(deal.getCommentCount())
              .price(deal.getPrice())
              .dealCategory(deal.getDealCategory())
              .thumbnailUrl(deal.getThumbnailUrl())
              .scrapedAt(Instant.now())
              .build();

          item.markNotified();
          itemRepository.save(item);
          sendNotification(site, item, keywordMatch);
          notifiedCount++;
          newCount++;
          if (keywordMatch.matched()) {
            keywordNotifiedCount++;
          }
        } else {
          skippedCount++;
        }
      }
    }

    log.info("사이트 처리 완료: siteCode={}, 신규={}, 업데이트={}, 알림={}(키워드={}), 건너뜀={}",
        site.getSiteCode(), newCount, updatedCount, notifiedCount, keywordNotifiedCount, skippedCount);
  }

  /**
   * 사이트에 등록된 필터가 없으면 기본 fallback 사용.
   * fallback: 조회수 >= minViewCount OR (추천수 >= minRecommendation AND 비추천+0 < 추천)
   */
  private Specification<ScrapedDeal> resolveNotificationSpec(HotDealSite site) {
    DealNotificationFilter filter = filterResolver.resolve(site.getSiteCode());
    if (filter != null) {
      return filter.createSpecification(site);
    }
    return new MinViewCountSpecification(site.getMinViewCount())
        .or(new RecommendationRatioSpecification(site.getMinRecommendation(), 0));
  }

  public int deleteItemsOlderThan(Instant cutoffDate) {
    return itemRepository.deleteByCreatedAtBefore(cutoffDate);
  }

  @Transactional(readOnly = true)
  public List<HotDealSiteResponse> getAllSites() {
    return siteRepository.findAll().stream()
        .map(this::toSiteResponse)
        .collect(Collectors.toList());
  }

  public HotDealSiteResponse updateSite(Long id, HotDealSiteUpdateRequest request) {
    HotDealSite site = siteRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("사이트를 찾을 수 없습니다: " + id));
    site.setEnabled(request.isEnabled());
    site.setMinRecommendation(request.getMinRecommendation());
    site.setMinViewCount(request.getMinViewCount());
    site.setMinCommentCount(request.getMinCommentCount());
    return toSiteResponse(site);
  }

  private HotDealSiteResponse toSiteResponse(HotDealSite site) {
    return HotDealSiteResponse.builder()
        .id(site.getId())
        .siteCode(site.getSiteCode().getCode())
        .siteName(site.getSiteName())
        .siteUrl(site.getSiteUrl())
        .boardUrl(site.getBoardUrl())
        .enabled(site.isEnabled())
        .requiresLogin(site.isRequiresLogin())
        .minRecommendation(site.getMinRecommendation())
        .minViewCount(site.getMinViewCount())
        .minCommentCount(site.getMinCommentCount())
        .createdAt(site.getCreated().getAt())
        .updatedAt(site.getUpdated().getAt())
        .build();
  }

  /**
   * Slack 알림 전송. 키워드에 매칭된 건은 @channel 멘션이 붙는다.
   */
  private void sendNotification(HotDealSite site, HotDealItem item, KeywordMatchResult keywordMatch) {
    notify.sendMessage(HotDealMessage.builder()
        .channel(SlackChannel.HOT_DEAL.getChannel())
        .notify(keywordMatch.matched())
        .matchedKeywords(keywordMatch.matchedKeywords())
        .siteName(site.getSiteName())
        .title(item.getTitle())
        .price(item.getPrice())
        .url(item.getUrl())
        .thumbnailUrl(item.getThumbnailUrl())
        .recommendationCount(item.getRecommendationCount())
        .unrecommendationCount(item.getUnrecommendationCount())
        .viewCount(item.getViewCount())
        .commentCount(item.getCommentCount())
        .dealCategory(item.getDealCategory())
        .build());

    log.info("핫딜 알림 전송: siteCode={}, externalId={}, 키워드매칭={}, 매칭키워드={}, title={}",
        site.getSiteCode(), item.getExternalId(), keywordMatch.matched(),
        keywordMatch.matchedKeywords(), item.getTitle());
  }
}
