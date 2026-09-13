package kr.hvy.blog.modules.stock.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 뉴스 제목 1건 (tb_stock_news). 본문은 없다. 룩어헤드 방어는 수집 시각이 아니라 publishedAt(기사 작성 시각) 기준이다.
 *
 * @param titleHash sha256(공백·구두점을 지운 제목) — 제공사가 같은 기사를 재전송하거나 공백만 다른 중복을 한 건으로 접는다
 * @param tickers   iscd1~5 중 6자리 종목코드만
 */
@Builder(toBuilder = true)
public record NewsItem(
    Long newsId,
    String source,
    String providerCode,
    String serialNo,
    Instant publishedAt,
    String title,
    byte[] titleHash,
    String categoryCode,
    String origin,
    List<String> tickers) {

  /**
   * 중복 제거 키: 공백·구두점·기호를 지운 제목의 SHA-256.
   */
  public static byte[] hashTitle(String title) {
    String normalized = title == null ? "" : title.replaceAll("[\\s\\p{Punct}\\u3000·…‘’“”「」『』【】]+", "").toLowerCase();
    try {
      return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
