package kr.hvy.blog.modules.stock.domain.model;

import java.time.Instant;

/**
 * 외부 사건 피드의 기사 1건 (제목만, 본문 없음). seenDate 는 GDELT 가 기사를 처음 본 시각(UTC)이며 tb_stock_news.published_at 이 된다.
 */
public record FeedArticle(String url, String title, Instant seenDate, String domain, String language) {
}
