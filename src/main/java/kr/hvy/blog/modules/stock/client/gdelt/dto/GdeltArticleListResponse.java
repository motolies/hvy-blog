package kr.hvy.blog.modules.stock.client.gdelt.dto;

import java.util.List;

/**
 * DOC 2.0 {@code mode=artlist&format=json} 응답. {@code {"articles":[{"url","url_mobile","title","seendate","socialimage","domain","language","sourcecountry"}]}}
 * (공식 문서 형식, 필드는 GdeltDocManualTest 로 실측).
 */
public record GdeltArticleListResponse(List<Article> articles) {

  public record Article(String url, String title, String seendate, String domain, String language, String sourcecountry) {
  }
}
