package kr.hvy.blog.modules.hotdeal.client.quasarzone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraper;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * 퀘이사존 핫딜 게시판 스크래퍼.
 *
 * 로그인 불필요, 정적 HTML.
 * 게시판 URL: https://quasarzone.com/bbs/qb_saleinfo
 * 3페이지 크롤링, CSS 클래스 셀렉터 기반 파싱, 종료 딜 자동 제외.
 */
@Slf4j
@Component
public class QuasarzoneScraper implements DealSiteScraper {

  private static final DealSiteCode SITE_CODE = DealSiteCode.QUASARZONE;
  private static final int TIMEOUT_MS = 10_000;
  private static final int MAX_PAGES = 3;
  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
  private static final String SITE_BASE_URL = "https://quasarzone.com";
  private static final String NOIMAGE_KEYWORD = "thumb_no_image";

  @Override
  public DealSiteCode getSiteCode() {
    return SITE_CODE;
  }

  @Override
  public List<ScrapedDeal> scrape(HotDealSite siteConfig) {
    String baseUrl = siteConfig.getSiteUrl() + siteConfig.getBoardUrl();
    List<ScrapedDeal> result = new ArrayList<>();

    for (int page = 1; page <= MAX_PAGES; page++) {
      String url = baseUrl + "?page=" + page;
      log.debug("퀘이사존 스크래핑: url={}", url);
      try {
        Document doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get();

        Elements rows = doc.select("div.market-type-list table tbody tr");
        for (Element row : rows) {
          ScrapedDeal deal = parseRow(row);
          if (deal != null) {
            result.add(deal);
          }
        }
      } catch (IOException e) {
        log.error("퀘이사존 스크래핑 중 네트워크 오류: page={}, error={}", page, e.getMessage());
      } catch (Exception e) {
        log.error("퀘이사존 스크래핑 중 파싱 오류: page={}, error={}", page, e.getMessage(), e);
      }
    }

    log.debug("퀘이사존 스크래핑 완료: dealCount={}", result.size());
    return result;
  }

  private ScrapedDeal parseRow(Element row) {
    Elements tds = row.select("td");
    if (tds.size() < 2) {
      return null;
    }

    Element recTd = tds.get(0);
    Element contentTd = tds.get(1);

    // 제목 링크에서 externalId 추출
    Element titleLink = contentTd.selectFirst("p.tit a.subject-link");
    if (titleLink == null) {
      return null;
    }
    String href = titleLink.attr("href");
    String externalId = extractExternalId(href);
    if (StringUtils.isBlank(externalId)) {
      return null;
    }

    // 종료 딜 제외: span.label 텍스트가 "종료"이거나 done 클래스 포함 시
    Element statusLabel = contentTd.selectFirst("p.tit span.label");
    if (statusLabel != null) {
      String labelText = statusLabel.text().trim();
      if ("종료".equals(labelText) || statusLabel.hasClass("done")) {
        return null;
      }
    }

    // 제목
    Element titleSpan = titleLink.selectFirst("span.ellipsis-with-reply-cnt");
    String title = titleSpan != null ? titleSpan.text().trim() : null;
    if (StringUtils.isBlank(title)) {
      return null;
    }

    // URL
    String postUrl = buildPostUrl(href);

    // 카테고리
    Element categoryElement = contentTd.selectFirst(".market-info-sub span.category");
    String dealCategory = categoryElement != null ? categoryElement.text().trim() : null;
    if (StringUtils.isBlank(dealCategory)) {
      dealCategory = null;
    }

    // 댓글수
    Element commentElement = contentTd.selectFirst("span.board-list-comment span.ctn-count");
    int commentCount = NumberUtils.toInt(
        commentElement != null ? commentElement.text().trim() : "0", 0);

    // 추천수: 첫 번째 td 내 span.num
    Element recElement = recTd.selectFirst("span.num");
    int recommendationCount = NumberUtils.toInt(
        recElement != null ? recElement.text().trim() : "0", 0);

    // 조회수: 축약 형식 파싱 필요 (1.2k, 7.2k 등)
    Element viewElement = contentTd.selectFirst(".market-info-sub span.count");
    int viewCount = parseAbbreviatedCount(
        viewElement != null ? viewElement.text().trim() : "0");

    // 작성자
    Element authorElement = contentTd.selectFirst(".market-info-sub span.user-nick-wrap");
    String author = authorElement != null ? authorElement.attr("data-nick") : null;
    if (StringUtils.isBlank(author)) {
      author = null;
    }

    // 썸네일
    String thumbnailUrl = extractThumbnailUrl(contentTd);

    // 가격
    Element priceElement = contentTd.selectFirst(".market-info-sub span.text-orange");
    String price = priceElement != null ? priceElement.text().trim() : null;
    if (StringUtils.isBlank(price)) {
      price = null;
    }

    return ScrapedDeal.builder()
        .externalId(externalId)
        .title(title)
        .url(postUrl)
        .recommendationCount(recommendationCount)
        .unrecommendationCount(0)
        .viewCount(viewCount)
        .commentCount(commentCount)
        .dealCategory(dealCategory)
        .thumbnailUrl(thumbnailUrl)
        .price(price)
        .author(author)
        .build();
  }

  /**
   * URL 마지막 경로 세그먼트를 externalId로 추출.
   * 예: "/bbs/qb_saleinfo/views/1937218" -> "1937218"
   */
  private String extractExternalId(String href) {
    if (StringUtils.isBlank(href)) {
      return null;
    }
    // 쿼리 파라미터 제거 후 마지막 경로 세그먼트 추출
    String path = href.split("\\?")[0];
    int lastSlash = path.lastIndexOf('/');
    if (lastSlash < 0 || lastSlash == path.length() - 1) {
      return null;
    }
    return path.substring(lastSlash + 1);
  }

  private String extractThumbnailUrl(Element contentTd) {
    Element thumbImg = contentTd.selectFirst(".thumb-wrap a.thumb img.maxImg");
    if (thumbImg == null) {
      return null;
    }
    String src = thumbImg.attr("src");
    if (StringUtils.isBlank(src) || src.contains(NOIMAGE_KEYWORD)) {
      return null;
    }
    if (src.startsWith("//")) {
      return "https:" + src;
    }
    return src;
  }

  private String buildPostUrl(String href) {
    if (StringUtils.isBlank(href)) {
      return SITE_BASE_URL;
    }
    if (href.startsWith("http")) {
      return href;
    }
    return SITE_BASE_URL + href;
  }

  /**
   * 축약 형식 조회수 파싱: "1.2k" -> 1200, "7.2k" -> 7200, "321" -> 321, "1.5m" -> 1500000
   */
  private int parseAbbreviatedCount(String text) {
    if (StringUtils.isBlank(text)) {
      return 0;
    }
    text = text.trim().toLowerCase();
    if (text.endsWith("k")) {
      double val = NumberUtils.toDouble(text.substring(0, text.length() - 1), 0);
      return (int) (val * 1000);
    }
    if (text.endsWith("m")) {
      double val = NumberUtils.toDouble(text.substring(0, text.length() - 1), 0);
      return (int) (val * 1000000);
    }
    return NumberUtils.toInt(text.replace(",", ""), 0);
  }
}
