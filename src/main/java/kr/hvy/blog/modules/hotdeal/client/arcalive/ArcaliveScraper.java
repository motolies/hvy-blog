package kr.hvy.blog.modules.hotdeal.client.arcalive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.hvy.blog.modules.hotdeal.application.dto.ScrapedDeal;
import kr.hvy.blog.modules.hotdeal.client.BrowserProperties;
import kr.hvy.blog.modules.hotdeal.client.DealSiteScraper;
import kr.hvy.blog.modules.hotdeal.domain.code.DealSiteCode;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealSite;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

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
  /**
   * 게시글 행 셀렉터. browserless waitForSelector 와 Jsoup 파싱이 같은 값을 써야 한다
   */
  private static final String DEAL_ROW_SELECTOR = "div.vrow.hybrid";
  /**
   * 스케줄러 lockAtMostFor(9분)를 침범하지 않도록 이 사이트에 허용하는 전체 시간
   */
  private static final Duration SCRAPE_BUDGET = Duration.ofMinutes(5);
  private static final int HTTP_OK = 200;
  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final int ERROR_BODY_MAX_LENGTH = 500;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final BrowserProperties browserProperties;
  /**
   * 모든 요청이 동일한 URI 이므로 기동 시 1회만 조립한다. 조립 실패 시 null
   */
  private final URI contentUri;

  public ArcaliveScraper(BrowserProperties browserProperties, ObjectMapper objectMapper) {
    this.browserProperties = browserProperties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(browserProperties.getConnectTimeout())
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    URI uri = null;
    try {
      uri = buildContentUri(browserProperties);
      log.info("browserless 호출 URI 확정: uri={}", uri);
    } catch (Exception e) {
      // 설정 오타로 블로그 전체 기동이 막히지 않도록 여기서 삼키고, 호출 시점에 IOException 으로 실패시킨다
      log.error("browserless 호출 URI 조립 실패: contentUrl={}, error={}",
          browserProperties.getContentUrl(), e.getMessage(), e);
    }
    this.contentUri = uri;
  }

  /**
   * browserless /content 호출 URI 를 조립한다.
   *
   * browserless v2 는 서버 전역 실행 옵션이 없어 puppeteer 기동 타임아웃을 요청 단위 launch 로만 올릴 수 있다.
   * 개별 쿼리 파라미터(stealth)와 launch JSON 은 browserless 가 병합하며 동일 키는 쿼리가 우선하므로 함께 써도 안전하다.
   * 설정 URL 에 같은 키가 이미 있어도 replaceQueryParam 으로 덮어써 중복을 막는다.
   * browserless 는 밀리초를 받으므로 설정(초)을 반드시 toMillis() 로 변환해 넘긴다.
   */
  static URI buildContentUri(BrowserProperties properties) {
    String launchJson = "{\"timeout\":" + properties.getLaunchTimeout().toMillis() + "}";
    return UriComponentsBuilder.fromUriString(properties.getContentUrl())
        .replaceQueryParam("stealth", "true")
        .replaceQueryParam("timeout", properties.getSessionTimeout().toMillis())
        .replaceQueryParam("launch", encodeLaunchParam(launchJson))
        .build(true)
        .toUri();
  }

  /**
   * launch JSON 을 표준 base64(패딩 유지)로 인코딩한 뒤 퍼센트 인코딩한다.
   *
   * browserless 는 [0-9a-zA-Z+/] 와 '=' 패딩만 base64 로 인정하므로(src/utils.ts isBase64)
   * URL-safe base64 나 패딩 제거를 쓰면 launch 가 조용히 무시된다.
   * 또한 Node URLSearchParams 가 쿼리의 raw '+' 를 공백으로 해석하므로 반드시 퍼센트 인코딩해야 한다.
   */
  static String encodeLaunchParam(String launchJson) {
    String base64 = Base64.getEncoder().encodeToString(launchJson.getBytes(StandardCharsets.UTF_8));
    return URLEncoder.encode(base64, StandardCharsets.UTF_8);
  }

  @Override
  public DealSiteCode getSiteCode() {
    return SITE_CODE;
  }

  @Override
  public List<ScrapedDeal> scrape(HotDealSite siteConfig) {
    String baseUrl = siteConfig.getSiteUrl() + siteConfig.getBoardUrl();
    List<ScrapedDeal> result = new ArrayList<>();
    Instant deadline = Instant.now().plus(SCRAPE_BUDGET);

    for (int page = 1; page <= MAX_PAGES; page++) {
      if (Instant.now().isAfter(deadline)) {
        log.warn("아카라이브 스크래핑 시간 예산 초과로 중단: page={}, budget={}", page, SCRAPE_BUDGET);
        break;
      }
      String targetUrl = baseUrl + "?p=" + page;
      log.debug("아카라이브 스크래핑: url={}", targetUrl);
      try {
        String html = fetchRenderedHtml(targetUrl);
        Document doc = Jsoup.parse(html);

        Elements rows = doc.select(DEAL_ROW_SELECTOR);
        for (Element row : rows) {
          ScrapedDeal deal = parseRow(siteConfig.getSiteUrl(), row);
          if (deal != null) {
            result.add(deal);
          }
        }
      } catch (IOException e) {
        // browserless 장애는 남은 페이지도 같은 방식으로 실패하며 페이지당 최대 타임아웃을 소모하므로 즉시 중단한다
        log.error("아카라이브 스크래핑 중 네트워크 오류(잔여 페이지 중단): page={}, error={}", page, e.getMessage());
        break;
      } catch (Exception e) {
        // 파싱 오류는 페이지별로 독립적일 수 있고 대기 비용이 없으므로 다음 페이지를 계속한다
        log.error("아카라이브 스크래핑 중 파싱 오류: page={}, error={}", page, e.getMessage(), e);
      }
    }

    log.debug("아카라이브 스크래핑 완료: dealCount={}", result.size());
    return result;
  }

  /**
   * browserless /content API를 호출하여 Chromium이 렌더링한 HTML을 반환받는다.
   *
   * 응답 대기 시간을 browserless 세션 상한보다 길게 잡아, 타임아웃 시 browserless 가 먼저 세션을 정리하고
   * 동시 실행 슬롯을 반납하도록 한다.
   */
  private String fetchRenderedHtml(String targetUrl) throws IOException {
    if (ObjectUtils.isEmpty(contentUri)) {
      throw new IOException("browserless 호출 URI 미설정");
    }

    String jsonBody = buildRequestBody(targetUrl);
    log.debug("browserless 요청: body={}", jsonBody);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(contentUri)
        .timeout(browserProperties.getRequestTimeout())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == HTTP_TOO_MANY_REQUESTS) {
        // CONCURRENT 슬롯 초과 - browserless 자체는 정상이므로 다른 오류와 구분해 남긴다
        log.warn("browserless 동시 실행 슬롯 초과: status={}, targetUrl={}", status, targetUrl);
        throw new IOException("browserless 슬롯 초과: status=" + status);
      }
      if (status != HTTP_OK) {
        log.error("browserless 응답 오류: status={}, uri={}, targetUrl={}, body={}",
            status, contentUri, targetUrl, StringUtils.abbreviate(response.body(), ERROR_BODY_MAX_LENGTH));
        throw new IOException("browserless 응답 오류: status=" + status);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("browserless 요청 중단", e);
    }
  }

  /**
   * browserless /content 요청 본문을 만든다.
   * targetUrl 이 DB(site_url + board_url) 에서 오므로 문자열 연결 대신 Jackson 으로 직렬화해 파손을 막는다.
   * puppeteer 는 밀리초를 받으므로 설정(초)을 반드시 toMillis() 로 변환해 넘긴다.
   */
  String buildRequestBody(String targetUrl) throws JsonProcessingException {
    Map<String, Object> gotoOptions = new LinkedHashMap<>();
    gotoOptions.put("waitUntil", "networkidle0");
    gotoOptions.put("timeout", browserProperties.getGotoTimeout().toMillis());

    Map<String, Object> waitForSelector = new LinkedHashMap<>();
    waitForSelector.put("selector", DEAL_ROW_SELECTOR);
    waitForSelector.put("timeout", browserProperties.getSelectorTimeout().toMillis());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("url", targetUrl);
    body.put("gotoOptions", gotoOptions);
    body.put("waitForSelector", waitForSelector);
    return objectMapper.writeValueAsString(body);
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
