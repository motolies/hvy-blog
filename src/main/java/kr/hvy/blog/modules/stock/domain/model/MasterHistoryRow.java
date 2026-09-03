package kr.hvy.blog.modules.stock.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import kr.hvy.blog.modules.stock.domain.code.MarketType;

/**
 * tb_stock_master_history(SCD2) 1행. snapshotHash 는 SCD 대상 컬럼만으로 계산해 "바뀌었는가"를 O(1) 로 판정한다.
 */
public record MasterHistoryRow(
    String ticker,
    LocalDate validFrom,
    String stockName,
    MarketType marketType,
    String securityGroup,
    String sectorMidCode,
    boolean kospi200,
    boolean krx300,
    boolean suspended,
    boolean administrative,
    boolean active,
    Long listedShares,
    String snapshotHash
) {

  /**
   * SCD 대상 컬럼으로 해시를 계산해 행을 만든다.
   */
  public static MasterHistoryRow of(String ticker, LocalDate validFrom, String stockName, MarketType marketType,
      String securityGroup, String sectorMidCode, boolean kospi200, boolean krx300, boolean suspended,
      boolean administrative, boolean active, Long listedShares) {
    String payload = String.join("|", stockName, marketType.getCode(), securityGroup, Objects.toString(sectorMidCode, ""),
        Boolean.toString(kospi200), Boolean.toString(krx300), Boolean.toString(suspended),
        Boolean.toString(administrative), Boolean.toString(active), Objects.toString(listedShares, ""));
    return new MasterHistoryRow(ticker, validFrom, stockName, marketType, securityGroup, sectorMidCode, kospi200,
        krx300, suspended, administrative, active, listedShares, sha256(payload));
  }

  /**
   * SHA-256 hex (64자).
   */
  static String sha256(String payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 미지원", e);
    }
  }
}
