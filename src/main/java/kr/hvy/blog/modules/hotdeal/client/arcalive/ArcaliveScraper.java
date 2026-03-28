package kr.hvy.blog.modules.hotdeal.client.arcalive;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 아카라이브 핫딜 게시판 스크래퍼.
 *
 * Cloudflare JS Challenge 보호로 인해 browserless(Chromium 사이드카) 컨테이너를 경유하여
 * 렌더링된 HTML을 받아온 뒤 Jsoup으로 파싱한다.
 * 게시판 URL: https://arca.live/b/hotdeal
 * 3페이지 크롤링, CSS 클래스 셀렉터 기반 파싱, 종료 딜 자동 제외.
 */
@Slf4j
@Component
public class ArcaliveScraper implements DealSiteScraper {

  private static final DealSiteCode SITE_CODE = DealSiteCode.ARCALIVE;
  private static final int MAX_PAGES = 3;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(40);

  private final HttpClient httpClient;

  @Value("${browser.content-url}")
  private String browserContentUrl;

  public ArcaliveScraper() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  @Override
  public DealSiteCode getSiteCode() {
    return SITE_CODE;
  }

  @Override
  public List<ScrapedDeal> scrape(HotDealSite siteConfig) {
    String baseUrl = siteConfig.getSiteUrl() + siteConfig.getBoardUrl();
    List<ScrapedDeal> result = new ArrayList<>();

    for (int page = 1; page <= MAX_PAGES; page++) {
      String targetUrl = baseUrl + "?p=" + page;
      log.debug("아카라이브 스크래핑: url={}", targetUrl);
      try {
        String html = fetchRenderedHtml(targetUrl);
        Document doc = Jsoup.parse(html);

        Elements rows = doc.select("div.vrow.hybrid");
        for (Element row : rows) {
          ScrapedDeal deal = parseRow(siteConfig.getSiteUrl(), row);
          if (deal != null) {
            result.add(deal);
          }
        }
      } catch (IOException e) {
        log.error("아카라이브 스크래핑 중 네트워크 오류: page={}, error={}", page, e.getMessage());
      } catch (Exception e) {
        log.error("아카라이브 스크래핑 중 파싱 오류: page={}, error={}", page, e.getMessage(), e);
      }
    }

    log.debug("아카라이브 스크래핑 완료: dealCount={}", result.size());
    return result;
  }

  /**
   * browserless /content API를 호출하여 Chromium이 렌더링한 HTML을 반환받는다.
   */
  private String fetchRenderedHtml(String targetUrl) throws IOException {
    String jsonBody = "{\"url\":\"" + targetUrl + "\","
        + "\"gotoOptions\":{\"waitUntil\":\"networkidle0\",\"timeout\":30000},"
        + "\"waitForSelector\":{\"selector\":\"div.vrow.hybrid\",\"timeout\":25000}}";
    log.debug("browserless 요청: body={}", jsonBody);

    String requestUrl = browserContentUrl + (browserContentUrl.contains("?") ? "&" : "?") + "stealth=true";
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(requestUrl))
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.error("browserless 응답 상세: status={}, url={}, body={}", response.statusCode(), request.uri(), response.body());
        throw new IOException("browserless 응답 오류: status=" + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("browserless 요청 중단", e);
    }
  }

  private ScrapedDeal parseRow(String siteUrl, Element row) {
    // 종료 딜 제외: deal-close 클래스 포함 시
    if (row.selectFirst(".deal-close") != null) {
      return null;
    }

    // 제목 링크에서 externalId 및 URL 추출
    Element titleLink = row.selectFirst("a.hybrid-title");
    if (titleLink == null) {
      return null;
    }
    String href = titleLink.attr("href");
    String externalId = extractExternalId(href);
    if (StringUtils.isBlank(externalId)) {
      return null;
    }

    // 제목: .info 자식 요소(댓글수 등) 제거 후 텍스트 추출
    Element titleClone = titleLink.clone();
    titleClone.select(".info").remove();
    String title = titleClone.text().trim();
    if (StringUtils.isBlank(title)) {
      return null;
    }

    // URL
    String postUrl = buildPostUrl(siteUrl, href);

    // 카테고리
    Element categoryElement = row.selectFirst(".badges a.badge");
    String dealCategory = categoryElement != null ? categoryElement.text().trim() : null;
    if (StringUtils.isBlank(dealCategory)) {
      dealCategory = null;
    }

    // 댓글수: "[13]" 형태에서 숫자만 추출
    Element commentElement = row.selectFirst(".comment-count");
    int commentCount = 0;
    if (commentElement != null) {
      String commentText = commentElement.text().replaceAll("[^0-9]", "");
      commentCount = NumberUtils.toInt(commentText, 0);
    }

    // 추천수
    Element rateElement = row.selectFirst(".vcol.col-rate");
    int recommendationCount = NumberUtils.toInt(
        rateElement != null ? rateElement.text().trim() : "0", 0);

    // 조회수
    Element viewElement = row.selectFirst(".vcol.col-view");
    int viewCount = NumberUtils.toInt(
        viewElement != null ? viewElement.text().trim().replace(",", "") : "0", 0);

    // 작성자
    Element authorElement = row.selectFirst(".col-author .user-info span[data-filter]");
    String author = authorElement != null ? authorElement.text().trim() : null;
    if (StringUtils.isBlank(author)) {
      author = null;
    }

    // 썸네일
    String thumbnailUrl = extractThumbnailUrl(row);

    // 가격
    Element priceElement = row.selectFirst(".deal-price");
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
   * 예: "/b/hotdeal/166062833?p=1" -> "166062833"
   */
  private String extractExternalId(String href) {
    if (StringUtils.isBlank(href)) {
      return null;
    }
    String path = href.split("\\?")[0];
    int lastSlash = path.lastIndexOf('/');
    if (lastSlash < 0 || lastSlash == path.length() - 1) {
      return null;
    }
    return path.substring(lastSlash + 1);
  }

  private String extractThumbnailUrl(Element row) {
    Element thumbImg = row.selectFirst(".vrow-preview img");
    if (thumbImg == null) {
      return null;
    }
    String src = thumbImg.attr("src");
    if (StringUtils.isBlank(src)) {
      return null;
    }
    if (src.startsWith("//")) {
      return "https:" + src;
    }
    return src;
  }

  private String buildPostUrl(String siteUrl, String href) {
    if (StringUtils.isBlank(href)) {
      return siteUrl;
    }
    if (href.startsWith("http")) {
      return href;
    }
    return siteUrl + href;
  }
}
