package kr.hvy.blog.modules.hotdeal.client.ruliweb;

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
 * 루리웹 핫딜 게시판 스크래퍼.
 *
 * 로그인 불필요, 정적 HTML.
 * 게시판 URL: https://bbs.ruliweb.com/market/board/1020
 * 3페이지 크롤링, CSS 클래스 셀렉터 기반 파싱, 공지/종료 자동 제외.
 */
@Slf4j
@Component
public class RuliwebScraper implements DealSiteScraper {

  private static final DealSiteCode SITE_CODE = DealSiteCode.RULIWEB;
  private static final int TIMEOUT_MS = 10_000;
  private static final int MAX_PAGES = 3;
  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

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
      log.debug("루리웹 스크래핑: url={}", url);
      try {
        Document doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get();

        Elements rows = doc.select("table.board_list_table tbody tr.table_body:not(.notice)");
        for (Element row : rows) {
          ScrapedDeal deal = parseRow(siteConfig.getSiteUrl(), row);
          if (deal != null) {
            result.add(deal);
          }
        }
      } catch (IOException e) {
        log.error("루리웹 스크래핑 중 네트워크 오류: page={}, error={}", page, e.getMessage());
      } catch (Exception e) {
        log.error("루리웹 스크래핑 중 파싱 오류: page={}, error={}", page, e.getMessage(), e);
      }
    }

    log.debug("루리웹 스크래핑 완료: dealCount={}", result.size());
    return result;
  }

  private ScrapedDeal parseRow(String siteUrl, Element row) {
    // 번호 셀: 비어있거나 숫자가 아니면 공지/BEST이므로 제외
    Element idCell = row.selectFirst("td.id");
    if (idCell == null) {
      return null;
    }
    String externalId = idCell.text().trim();
    if (externalId.isEmpty() || !NumberUtils.isDigits(externalId)) {
      return null;
    }

    // 제목 셀: [종료] 포함 시 제외
    Element subjectCell = row.selectFirst("td.subject");
    if (subjectCell == null) {
      return null;
    }
    if (subjectCell.text().contains("[종료]")) {
      return null;
    }

    // 제목 링크
    Element titleLink = subjectCell.selectFirst("a.subject_link");
    if (titleLink == null) {
      return null;
    }
    String title = titleLink.text().trim();
    if (StringUtils.isBlank(title)) {
      return null;
    }
    String postUrl = buildPostUrl(siteUrl, titleLink.attr("href"));

    // 카테고리
    Element categoryElement = row.selectFirst("td.divsn a");
    String dealCategory = categoryElement != null ? categoryElement.text().trim() : null;
    if (StringUtils.isBlank(dealCategory)) {
      dealCategory = null;
    }

    // 댓글수: "(42)" 형태에서 괄호 제거 후 숫자 파싱
    Element replyElement = subjectCell.selectFirst(".num_reply");
    int commentCount = 0;
    if (replyElement != null) {
      String replyText = replyElement.text().trim()
          .replace("(", "")
          .replace(")", "");
      commentCount = NumberUtils.toInt(replyText, 0);
    }

    // 추천수
    Element recomdCell = row.selectFirst("td.recomd");
    int recommendationCount = NumberUtils.toInt(
        recomdCell != null ? recomdCell.text().trim() : "0", 0);

    // 조회수: 쉼표 제거 후 정수 변환
    Element hitCell = row.selectFirst("td.hit");
    int viewCount = NumberUtils.toInt(
        hitCell != null ? hitCell.text().trim().replace(",", "") : "0", 0);

    // 작성자
    Element authorElement = row.selectFirst("td.writer a");
    String author = authorElement != null ? authorElement.text().trim() : null;

    return ScrapedDeal.builder()
        .externalId(externalId)
        .title(title)
        .url(postUrl)
        .recommendationCount(recommendationCount)
        .unrecommendationCount(0)
        .viewCount(viewCount)
        .commentCount(commentCount)
        .dealCategory(dealCategory)
        .thumbnailUrl(null)
        .price(null)
        .author(author)
        .build();
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
