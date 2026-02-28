package kr.hvy.blog.modules.hotdeal.application.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.application.filter.DealNotificationFilter;
import kr.hvy.blog.modules.hotdeal.application.filter.DealNotificationFilterResolver;
import kr.hvy.blog.modules.hotdeal.application.specification.MinViewCountSpecification;
import kr.hvy.blog.modules.hotdeal.application.specification.RecommendationRatioSpecification;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraper;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraperResolver;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealItem;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import kr.hvy.blog.modules.hotdeal.repository.HotDealItemRepository;
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
  private final DealSiteScraperResolver scraperResolver;
  private final DealNotificationFilterResolver filterResolver;
  private final Notify notify;

  /**
   * 활성화된 모든 사이트를 스크래핑하고 필터 조건 충족 시 Slack 알림 전송.
   * 사이트별 에러 격리: 한 사이트가 실패해도 나머지 사이트는 계속 처리.
   */
  public void scrapeAndNotify() {
    List<HotDealSite> enabledSites = siteRepository.findByEnabledTrue();
    log.info("핫딜 스크래핑 시작: 활성 사이트 수={}", enabledSites.size());

    for (HotDealSite site : enabledSites) {
      try {
        processSite(site);
      } catch (Exception e) {
        log.error("사이트 처리 중 오류 (다음 사이트 계속 진행): siteCode={}, error={}",
            site.getSiteCode(), e.getMessage(), e);
      }
    }
  }

  private void processSite(HotDealSite site) {
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

    for (ScrapedDeal deal : deals) {
      Optional<HotDealItem> existing = itemRepository.findBySiteAndExternalId(site, deal.getExternalId());

      if (existing.isPresent()) {
        HotDealItem item = existing.get();
        item.updateCounts(
            deal.getRecommendationCount(),
            deal.getUnrecommendationCount(),
            deal.getViewCount(),
            deal.getCommentCount());
        item.updateThumbnailUrl(deal.getThumbnailUrl());

        if (!item.isNotified() && notificationSpec.isSatisfiedBy(deal)) {
          sendNotification(site, item);
          item.markNotified();
          notifiedCount++;
        }
        updatedCount++;
      } else {
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
            .scrapedAt(LocalDateTime.now())
            .build();

        itemRepository.save(item);

        if (notificationSpec.isSatisfiedBy(deal)) {
          sendNotification(site, item);
          item.markNotified();
          notifiedCount++;
        }
        newCount++;
      }
    }

    log.info("사이트 처리 완료: siteCode={}, 신규={}, 업데이트={}, 알림={}",
        site.getSiteCode(), newCount, updatedCount, notifiedCount);
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

  private void sendNotification(HotDealSite site, HotDealItem item) {
    notify.sendMessage(HotDealMessage.builder()
        .channel(SlackChannel.HOT_DEAL.getChannel())
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

    log.info("핫딜 알림 전송: siteCode={}, externalId={}, title={}",
        site.getSiteCode(), item.getExternalId(), item.getTitle());
  }
}
