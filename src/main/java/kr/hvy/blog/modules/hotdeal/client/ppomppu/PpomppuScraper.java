package kr.hvy.blog.modules.hotdeal.client.ppomppu;

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
 * 뽐뿌 게시판 스크래퍼.
 *
 * 로그인 불필요, 정적 HTML.
 * 게시판 URL: https://www.ppomppu.co.kr/zboard/zboard.php?id=ppomppu
 * 3페이지 크롤링, CSS 클래스 셀렉터 기반 파싱, 광고 자동 제외.
 */
@Slf4j
@Component
public class PpomppuScraper implements DealSiteScraper {

  private static final DealSiteCode SITE_CODE = DealSiteCode.PPOMPPU;
  private static final int TIMEOUT_MS = 10_000;
  private static final int MAX_PAGES = 3;
  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
  private static final String NOIMAGE_URL = "noimage_60x50.jpg";

  @Override
  public DealSiteCode getSiteCode() {
    return SITE_CODE;
  }

  @Override
  public List<ScrapedDeal> scrape(HotDealSite siteConfig) {
    String baseUrl = siteConfig.getSiteUrl() + siteConfig.getBoardUrl();
    List<ScrapedDeal> result = new ArrayList<>();

    for (int page = 1; page <= MAX_PAGES; page++) {
      String url = baseUrl + "&page=" + page;
      log.debug("뽐뿌 스크래핑: url={}", url);
      try {
        Document doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get();

        Elements rows = doc.select("tr.baseList");
        for (Element row : rows) {
          ScrapedDeal deal = parseRow(siteConfig.getSiteUrl(), row);
          if (deal != null) {
            result.add(deal);
          }
        }
      } catch (IOException e) {
        log.error("뽐뿌 스크래핑 중 네트워크 오류: page={}, error={}", page, e.getMessage());
      } catch (Exception e) {
        log.error("뽐뿌 스크래핑 중 파싱 오류: page={}, error={}", page, e.getMessage(), e);
      }
    }

    log.debug("뽐뿌 스크래핑 완료: dealCount={}", result.size());
    return result;
  }

  private ScrapedDeal parseRow(String siteUrl, Element row) {
    // 번호 셀: 광고글은 <img> 포함
    Element numbCell = row.selectFirst(".baseList-numb");
    if (numbCell == null || numbCell.selectFirst("img") != null) {
      return null;
    }
    String externalId = numbCell.text().trim();
    if (!NumberUtils.isDigits(externalId) || externalId.isEmpty()) {
      return null;
    }

    // 제목 셀
    Element titleCell = row.selectFirst(".baseList-title");
    if (titleCell == null) {
      return null;
    }
    Element titleLink = titleCell.selectFirst("a[href*='view.php?id=ppomppu']");
    if (titleLink == null) {
      return null;
    }
    String title = titleLink.text().trim();
    if (StringUtils.isBlank(title)) {
      return null; // 품절 등으로 제목이 비어있는 경우
    }
    String postUrl = buildPostUrl(siteUrl, titleLink.attr("href"));

    // 썸네일 이미지
    String thumbnailUrl = extractThumbnailUrl(row);

    // 카테고리
    String dealCategory = extractCategory(titleCell);

    // 추천/비추천: "19 - 3" 형식
    Element recCell = row.selectFirst(".baseList-rec");
    int[] recCounts = parseRecommendation(recCell != null ? recCell.text().trim() : "");

    // 조회수
    Element viewCell = row.selectFirst(".baseList-views");
    int viewCount = NumberUtils.toInt(
        viewCell != null ? viewCell.text().trim().replace(",", "") : "0", 0);

    // 댓글수
    Element commentCell = row.selectFirst(".baseList-c");
    int commentCount = NumberUtils.toInt(
        commentCell != null ? commentCell.text().trim() : "0", 0);

    return ScrapedDeal.builder()
        .externalId(externalId)
        .title(title)
        .url(postUrl)
        .recommendationCount(recCounts[0])
        .unrecommendationCount(recCounts[1])
        .viewCount(viewCount)
        .commentCount(commentCount)
        .dealCategory(dealCategory)
        .thumbnailUrl(thumbnailUrl)
        .build();
  }

  private String extractThumbnailUrl(Element row) {
    Element thumbImg = row.selectFirst("a.baseList-thumb img");
    if (thumbImg == null) {
      return null;
    }
    String src = thumbImg.attr("src");
    if (StringUtils.isBlank(src) || src.contains(NOIMAGE_URL)) {
      return null;
    }
    return src.startsWith("//") ? "https:" + src : src;
  }

  private String extractCategory(Element titleCell) {
    Elements categoryElements = titleCell.select("font");
    if (!categoryElements.isEmpty()) {
      String text = categoryElements.last().text().trim();
      if (text.startsWith("[") && text.endsWith("]")) {
        return text.substring(1, text.length() - 1);
      }
    }

    String fullText = titleCell.text();
    int start = fullText.lastIndexOf("[");
    int end = fullText.lastIndexOf("]");
    if (start >= 0 && end > start) {
      return fullText.substring(start + 1, end);
    }
    return null;
  }

  private String buildPostUrl(String siteUrl, String relativeUrl) {
    if (StringUtils.isBlank(relativeUrl)) {
      return siteUrl;
    }
    if (relativeUrl.startsWith("http")) {
      return relativeUrl;
    }
    if (relativeUrl.startsWith("/")) {
      return siteUrl + relativeUrl;
    }
    return siteUrl + "/zboard/" + relativeUrl;
  }

  /**
   * 추천/비추천 수 파싱: "19 - 3" → {19, 3}, "5" → {5, 0}, "" → {0, 0}
   */
  private int[] parseRecommendation(String text) {
    if (StringUtils.isBlank(text)) {
      return new int[]{0, 0};
    }
    String[] parts = text.split("-");
    int up = NumberUtils.toInt(parts[0].trim(), 0);
    int down = parts.length > 1 ? NumberUtils.toInt(parts[1].trim(), 0) : 0;
    return new int[]{up, down};
  }
}
