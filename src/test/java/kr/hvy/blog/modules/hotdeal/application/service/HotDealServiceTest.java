package kr.hvy.blog.modules.hotdeal.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Optional;
import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraper;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraperResolver;
import kr.hvy.blog.modules.hotdeal.application.filter.DealNotificationFilterResolver;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealItem;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import kr.hvy.blog.modules.hotdeal.repository.HotDealItemRepository;
import kr.hvy.blog.modules.hotdeal.repository.HotDealKeywordRepository;
import kr.hvy.blog.modules.hotdeal.repository.HotDealSiteRepository;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.message.HotDealMessage;
import kr.hvy.common.infrastructure.notification.slack.message.SlackMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HotDealServiceTest {

  @Mock
  HotDealSiteRepository siteRepository;

  @Mock
  HotDealItemRepository itemRepository;

  @Mock
  HotDealKeywordRepository keywordRepository;

  @Mock
  DealSiteScraperResolver scraperResolver;

  @Mock
  DealNotificationFilterResolver filterResolver;

  @Mock
  DealSiteScraper scraper;

  @Mock
  Notify notify;

  @InjectMocks
  HotDealService hotDealService;

  @Captor
  ArgumentCaptor<SlackMessage> messageCaptor;

  private HotDealSite site;

  @BeforeEach
  void setUp() {
    // 임계값을 극단적으로 높여 fallback Specification이 항상 실패하게 만든다.
    // 이렇게 하면 알림 여부를 결정하는 요인이 키워드 매칭뿐이 된다.
    site = HotDealSite.builder()
        .id(1L)
        .siteCode(DealSiteCode.PPOMPPU)
        .siteName("뽐뿌")
        .siteUrl("https://www.ppomppu.co.kr")
        .boardUrl("/zboard/zboard.php?id=ppomppu")
        .enabled(true)
        .minRecommendation(999999)
        .minViewCount(999999)
        .minCommentCount(999999)
        .build();
  }

  private ScrapedDeal deal(String title) {
    // 추천/조회/댓글 모두 0 → 임계값 조건은 확실히 탈락한다
    return ScrapedDeal.builder()
        .externalId("12345")
        .title(title)
        .url("https://www.ppomppu.co.kr/view.php?id=12345")
        .recommendationCount(0)
        .unrecommendationCount(0)
        .viewCount(0)
        .commentCount(0)
        .build();
  }

  private void givenScraping(ScrapedDeal... deals) {
    given(siteRepository.findByEnabledTrue()).willReturn(List.of(site));
    given(scraperResolver.resolve(DealSiteCode.PPOMPPU)).willReturn(scraper);
    given(filterResolver.resolve(DealSiteCode.PPOMPPU)).willReturn(null);
    given(scraper.scrape(site)).willReturn(List.of(deals));
  }

  private void givenKeywords(String... keywords) {
    given(keywordRepository.findByEnabledTrueOrderByKeywordAsc())
        .willReturn(java.util.Arrays.stream(keywords)
            .map(k -> HotDealKeyword.create(k, true))
            .toList());
  }

  @Nested
  @DisplayName("키워드 임계값 우회")
  class KeywordBypassesThreshold {

    @Test
    @DisplayName("키워드에 매칭되면 임계값 미달이어도 저장하고 알림을 보낸다")
    void scrapeAndNotify_keywordMatched_savesAndNotifiesDespiteThreshold() {
      // Given
      givenKeywords("닌텐도");
      givenScraping(deal("[G마켓] 닌텐도 스위치 OLED 특가"));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.empty());

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(itemRepository).should().save(any(HotDealItem.class));
      then(notify).should().sendMessage(any(HotDealMessage.class));
    }

    @Test
    @DisplayName("키워드 미매칭이고 임계값도 미달이면 저장도 알림도 하지 않는다")
    void scrapeAndNotify_noMatchAndBelowThreshold_skips() {
      // Given
      givenKeywords("닌텐도");
      givenScraping(deal("[다나와] RTX 5070 최저가"));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.empty());

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(itemRepository).should(never()).save(any());
      then(notify).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("활성 키워드가 없으면 기존 임계값 동작을 그대로 유지한다")
    void scrapeAndNotify_noKeywords_keepsThresholdBehavior() {
      // Given
      givenKeywords();
      givenScraping(deal("[G마켓] 닌텐도 스위치 OLED 특가"));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.empty());

      // When
      hotDealService.scrapeAndNotify();

      // Then - 임계값 미달이므로 알림이 없어야 한다
      then(itemRepository).should(never()).save(any());
      then(notify).shouldHaveNoInteractions();
    }
  }

  @Nested
  @DisplayName("Slack 메시지 구성")
  class SlackMessageComposition {

    @Test
    @DisplayName("키워드 매칭 시 notify=true와 매칭 키워드가 메시지에 담긴다")
    void sendNotification_keywordMatched_setsNotifyAndKeywords() {
      // Given
      givenKeywords("닌텐도");
      givenScraping(deal("[G마켓] 닌텐도 스위치 OLED 특가"));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.empty());

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(notify).should().sendMessage(messageCaptor.capture());
      HotDealMessage message = (HotDealMessage) messageCaptor.getValue();
      assertThat(message.isNotify()).isTrue();
      assertThat(message.getMatchedKeywords()).containsExactly("닌텐도");
    }

    @Test
    @DisplayName("임계값만 충족하면 notify=false로 전송한다")
    void sendNotification_thresholdOnly_setsNotifyFalse() {
      // Given - 조회수 임계값을 낮춰 임계값 경로로 알림이 나가게 한다
      site.setMinViewCount(10);
      givenKeywords("닌텐도");
      given(siteRepository.findByEnabledTrue()).willReturn(List.of(site));
      given(scraperResolver.resolve(DealSiteCode.PPOMPPU)).willReturn(scraper);
      given(filterResolver.resolve(DealSiteCode.PPOMPPU)).willReturn(null);
      given(scraper.scrape(site)).willReturn(List.of(ScrapedDeal.builder()
          .externalId("12345")
          .title("[다나와] RTX 5070 최저가")
          .url("https://www.ppomppu.co.kr/view.php?id=12345")
          .viewCount(5000)
          .build()));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.empty());

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(notify).should().sendMessage(messageCaptor.capture());
      HotDealMessage message = (HotDealMessage) messageCaptor.getValue();
      assertThat(message.isNotify()).isFalse();
      assertThat(message.getMatchedKeywords()).isEmpty();
    }
  }

  @Nested
  @DisplayName("기존 아이템 처리")
  class ExistingItem {

    @Test
    @DisplayName("이미 알림된 아이템은 키워드에 매칭돼도 재알림하지 않는다")
    void existingNotifiedItem_keywordMatched_doesNotRenotify() {
      // Given
      HotDealItem existing = HotDealItem.builder()
          .site(site)
          .externalId("12345")
          .title("[G마켓] 닌텐도 스위치 OLED 특가")
          .url("https://www.ppomppu.co.kr/view.php?id=12345")
          .build();
      existing.markNotified();

      givenKeywords("닌텐도");
      givenScraping(deal("[G마켓] 닌텐도 스위치 OLED 특가"));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.of(existing));

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(notify).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("알림되지 않은 기존 아이템이 키워드에 매칭되면 알림하고 notified로 표시한다")
    void existingUnnotifiedItem_keywordMatched_notifiesAndMarks() {
      // Given
      HotDealItem existing = HotDealItem.builder()
          .site(site)
          .externalId("12345")
          .title("[G마켓] 닌텐도 스위치 OLED 특가")
          .url("https://www.ppomppu.co.kr/view.php?id=12345")
          .notified(false)
          .build();

      givenKeywords("닌텐도");
      givenScraping(deal("[G마켓] 닌텐도 스위치 OLED 특가"));
      given(itemRepository.findBySiteAndExternalId(site, "12345")).willReturn(Optional.of(existing));

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(notify).should().sendMessage(any(HotDealMessage.class));
      assertThat(existing.isNotified()).isTrue();
    }
  }

  @Nested
  @DisplayName("키워드 로딩")
  class KeywordLoading {

    @Test
    @DisplayName("키워드는 사이트 수와 무관하게 1회만 조회한다")
    void scrapeAndNotify_multipleSites_loadsKeywordsOnce() {
      // Given - 사이트 3개
      HotDealSite site2 = HotDealSite.builder()
          .id(2L).siteCode(DealSiteCode.RULIWEB).siteName("루리웹")
          .siteUrl("https://bbs.ruliweb.com").boardUrl("/market/board/1020")
          .minRecommendation(999999).minViewCount(999999).minCommentCount(999999)
          .build();
      HotDealSite site3 = HotDealSite.builder()
          .id(3L).siteCode(DealSiteCode.QUASARZONE).siteName("퀘이사존")
          .siteUrl("https://quasarzone.com").boardUrl("/bbs/qb_saleinfo")
          .minRecommendation(999999).minViewCount(999999).minCommentCount(999999)
          .build();

      givenKeywords("닌텐도");
      given(siteRepository.findByEnabledTrue()).willReturn(List.of(site, site2, site3));
      given(scraperResolver.resolve(any(DealSiteCode.class))).willReturn(null);

      // When
      hotDealService.scrapeAndNotify();

      // Then
      then(keywordRepository).should(times(1)).findByEnabledTrueOrderByKeywordAsc();
    }
  }
}
